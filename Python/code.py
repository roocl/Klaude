import ast
import json
import os
import random
import re
import subprocess
import threading
import time

from dataclasses import asdict, dataclass, field
from datetime import datetime
from pathlib import Path

import yaml
from anthropic import Anthropic
from dotenv import load_dotenv

# 从 .env 文件加载环境变量到 os.environ，true代表覆盖
load_dotenv(override=True)
# 兼容第三方端点
if os.getenv("ANTHROPIC_BASE_URL"):
    # 移除官方鉴权
    os.environ.pop("ANTHROPIC_AUTH_TOKEN", None)

WORKDIR = Path.cwd()
TOOL_RESULTS_DIR = WORKDIR / ".task_outputs" / "tool-results"
SKILLS_DIR = WORKDIR / "skills"
TRANSCRIPT_DIR = WORKDIR / ".transcripts"
MEMORY_DIR = WORKDIR / ".memory"
MEMORY_DIR.mkdir(exist_ok=True)
MEMORY_INDEX = MEMORY_DIR / "MEMORY.md"
TASKS_DIR = WORKDIR / ".tasks"
TASKS_DIR.mkdir(exist_ok=True)
DURABLE_PATH = WORKDIR / ".scheduled_tasks.json"
MAILBOX_DIR = WORKDIR / ".mailboxes"
MAILBOX_DIR.mkdir(exist_ok=True)
WORKTREES_DIR = WORKDIR / ".worktrees"
WORKTREES_DIR.mkdir(exist_ok=True)

client = Anthropic(base_url=os.getenv("ANTHROPIC_BASE_URL"))
PRIMARY_MODEL = os.environ["MODEL_ID"]
FALLBACK_MODEL = os.getenv("FALLBACK_MODEL_ID")
CURRENT_TODOS: list[dict] = []
VALID_WT_NAME = re.compile(r"^[A-Za-z0-9._-]{1,64}$")

""" v19 MCP """

# 发现并调用 MCP server上的工具
class MCPClient:

    def __init__(self, name: str):
        self.name = name
        self.tools: list[dict] = []
        self._handlers: dict[str, callable] = {}

    def register(self, tool_defs: list[dict],
                 handlers: dict[str, callable]):
        self.tools = tool_defs
        self._handlers = handlers

    def call_tool(self, tool_name: str, args: dict) -> str:
        handler = self._handlers.get(tool_name)
        if not handler:
            return f"MCP error: unknown tool '{tool_name}'"
        try:
            return handler(**args)
        except Exception as e:
            return f"MCP error: {e}"


mcp_clients: dict[str, MCPClient] = {}

_DISALLOWED_CHARS = re.compile(r'[^a-zA-Z0-9_-]')


# 规范化mcp工具名，将将非[a-zA-Z0-9_-]的字符替换为下划线
def normalize_mcp_name(name: str) -> str:
    return _DISALLOWED_CHARS.sub('_', name)


def _mock_server_docs():
    client = MCPClient("docs")
    client.register(
        tool_defs=[
            {"name": "search", "description": "Search documentation. (readOnly)",
             "inputSchema": {"type": "object",
                             "properties": {"query": {"type": "string"}},
                             "required": ["query"]}},
            {"name": "get_version", "description": "Get API version. (readOnly)",
             "inputSchema": {"type": "object", "properties": {},
                             "required": []}},
        ],
        handlers={
            "search": lambda query: f"[docs] Found 3 results for '{query}'",
            "get_version": lambda: "[docs] API v2.1.0",
        })
    return client


def _mock_server_deploy():
    client = MCPClient("deploy")
    client.register(
        tool_defs=[
            {"name": "trigger",
             "description": "Trigger a deployment. (destructive — requires approval in real CC)",
             "inputSchema": {"type": "object",
                             "properties": {"service": {"type": "string"}},
                             "required": ["service"]}},
            {"name": "status", "description": "Check deployment status. (readOnly)",
             "inputSchema": {"type": "object",
                             "properties": {"service": {"type": "string"}},
                             "required": ["service"]}},
        ],
        handlers={
            "trigger": lambda service: f"[deploy] Triggered: {service}",
            "status": lambda service: f"[deploy] {service}: running (v1.4.2)",
        })
    return client


MOCK_SERVERS = {
    "docs": _mock_server_docs,
    "deploy": _mock_server_deploy,
}


def connect_mcp(name: str) -> str:
    if name in mcp_clients:
        return f"MCP server '{name}' already connected"
    factory = MOCK_SERVERS.get(name)
    if not factory:
        available = ", ".join(MOCK_SERVERS.keys())
        return f"Unknown server '{name}'. Available: {available}"
    mcp_client = factory()
    mcp_clients[name] = mcp_client
    tool_names = [t["name"] for t in mcp_client.tools]
    print(f"  \033[31m[mcp] connected: {name} → {tool_names}\033[0m")
    return (f"Connected to MCP server '{name}'. "
            f"Discovered {len(mcp_client.tools)} tools: {', '.join(tool_names)}")


# 将内置工具 + 所有 MCP 工具组装到一个工具池中
def assemble_tool_pool() -> tuple[list[dict], dict]:
    tools = list(BUILTIN_TOOLS)
    handlers = dict(BUILTIN_HANDLERS)
    for server_name, mcp_client in mcp_clients.items():
        safe_server = normalize_mcp_name(server_name)
        for tool_def in mcp_client.tools:
            safe_tool = normalize_mcp_name(tool_def["name"])
            prefixed = f"mcp__{safe_server}__{safe_tool}"
            tools.append({
                "name": prefixed,
                "description": tool_def.get("description", ""),
                "input_schema": tool_def.get("inputSchema", {}),
            })
            # *：禁止位置传参，之后参数按name=value形式传入，所有lambda共享同一个mcp_client（循环结束后最后一个值）
            # **kw：收集所有剩余关键字参数（即用户调用工具时传入的参数）
            # c.call_tool(t, kw)：调用MCPClient的call_tool方法，传入工具名t和参数字典kw
            handlers[prefixed] = (
                lambda *, c=mcp_client, t=tool_def["name"], **kw: c.call_tool(t, kw))
    return tools, handlers


def run_connect_mcp(name: str) -> str:
    return connect_mcp(name)


# v18 worktree system

# 验证工作树名称，无效返回错误信息，有效则返回 None
def validate_worktree_name(name: str) -> str | None:
    if not name:
        return "Worktree name cannot be empty"
    if name == "." or name == "..":
        return f"'{name}' is not a valid worktree name"
    if not VALID_WT_NAME.match(name):
        return (
            f"Invalid worktree name '{name}': "
            "only letters, digits, dots, underscores, dashes (1-64 chars)"
        )
    return None


# 运行 git 命令，返回ok状态与输出
def run_git(args: list[str]) -> tuple[bool, str]:
    try:
        r = subprocess.run(
            ["git"] + args, cwd=WORKDIR, capture_output=True, text=True, timeout=30
        )
        out = (r.stdout + r.stderr).strip()
        out = out[:5000] if out else "(no output)"
        return r.returncode == 0, out
    except subprocess.TimeoutExpired:
        return False, "Error: git timeout"


# 将 worktree 的生命周期事件追加记录到events.jsonl中
def log_event(event_type: str, worktree_name: str, task_id: str = ""):
    event = {
        "type": event_type,
        "worktree": worktree_name,
        "task_id": task_id,
        "ts": time.time(),
    }
    events_file = WORKTREES_DIR / "events.jsonl"
    with open(events_file, "a") as f:
        f.write(json.dumps(event) + "\n")


# 创建一个带有专用分支的 git 工作树。可以选择绑定到某个特定任务
def create_worktree(name: str, task_id: str = "") -> str:
    err = validate_worktree_name(name)
    if err:
        return f"Error: {err}"
    path = WORKTREES_DIR / name
    if path.exists():
        return f"Worktree '{name}' already exists at {path}"
    ok, result = run_git(["worktree", "add", str(path), "-b", f"wt/{name}", "HEAD"])
    if not ok:
        return f"Git error: {result}"
    if task_id:
        bind_task_to_worktree(task_id, name)
    log_event("create", name, task_id)
    print(f"  \033[33m[worktree] created: {name} at {path}\033[0m")
    return f"Worktree '{name}' created at {path}"


# 绑定任务到工作树上，更新worktree字段
def bind_task_to_worktree(task_id: str, worktree_name: str):
    task = load_task(task_id)
    task.worktree = worktree_name
    save_task(task)
    print(f"  \033[33m[bind] {task.subject} → worktree:{worktree_name}\033[0m")


# 统计工作树中未提交的文件和提交次数
def _count_worktree_changes(path: Path) -> tuple[int, int]:
    try:
        r1 = subprocess.run(
            ["git", "status", "--porcelain"],
            cwd=path,
            capture_output=True,
            text=True,
            timeout=10,
        )
        files = len([l for l in r1.stdout.strip().splitlines() if l.strip()])
        r2 = subprocess.run(
            ["git", "log", "@{push}..HEAD", "--oneline"],
            cwd=path,
            capture_output=True,
            text=True,
            timeout=10,
        )
        commits = len([l for l in r2.stdout.strip().splitlines() if l.strip()])
        return files, commits
    except Exception:
        return -1, -1


# 移除工作树。如果存在未提交的更改，则拒绝移除，除非指定 discard_changes。
def remove_worktree(name: str, discard_changes: bool = False) -> str:
    err = validate_worktree_name(name)
    if err:
        return err
    path = WORKTREES_DIR / name
    if not path.exists():
        return f"Worktree '{name}' not found"
    if not discard_changes:
        files, commits = _count_worktree_changes(path)
        if files < 0:
            return (
                f"Cannot verify worktree '{name}' status. "
                "Use discard_changes=true to force removal."
            )
        if files > 0 or commits > 0:
            return (
                f"Worktree '{name}' has {files} uncommitted file(s) "
                f"and {commits} unpushed commit(s). "
                "Use discard_changes=true to force removal, "
                "or keep_worktree to preserve for review."
            )
    ok1, _ = run_git(["worktree", "remove", str(path), "--force"])
    if not ok1:
        return f"Failed to remove worktree directory for '{name}'"
    # 移除worktree目录后强制删除对应的git分支
    run_git(["branch", "-D", f"wt/{name}"])
    log_event("remove", name)
    print(f"  \033[33m[worktree] removed: {name}\033[0m")
    return f"Worktree '{name}' removed"


# 保留工作树以供手动审核，分支已保留。
def keep_worktree(name: str) -> str:
    err = validate_worktree_name(name)
    if err:
        return err
    log_event("keep", name)
    print(f"  \033[36m[worktree] kept: {name}\033[0m")
    return f"Worktree '{name}' kept for review (branch: wt/{name})"


# 基于文件的收信箱，每个agent都有一个.jsonl收信箱
class MessageBus:

    def send(
        self,
        from_agent: str,
        to_agent: str,
        content: str,
        msg_type: str = "message",
        metadata: dict = None,
    ):
        msg = {
            "from": from_agent,
            "to": to_agent,
            "content": content,
            "type": msg_type,
            "ts": time.time(),
            "metadata": metadata or {},
        }
        inbox = MAILBOX_DIR / f"{to_agent}.jsonl"
        with open(inbox, "a") as f:
            f.write(json.dumps(msg) + "\n")
        print(
            f"  \033[33m[bus] {from_agent} → {to_agent}: "
            f"({msg_type}) {content[:50]}\033[0m"
        )

    def read_inbox(self, agent: str) -> list[dict]:
        inbox = MAILBOX_DIR / f"{agent}.jsonl"
        if not inbox.exists():
            return []
        msgs = [
            json.loads(line) for line in inbox.read_text().splitlines() if line.strip()
        ]
        inbox.unlink()  # consume: read + delete
        return msgs


BUS = MessageBus()

# 追踪生成的队友
active_teammates: dict[str, bool] = {}

""" v16 协议状态 """


@dataclass
class ProtocolState:
    request_id: str
    type: str  # "shutdown" | "plan_approval"
    sender: str
    target: str
    status: str  # pending | approved | rejected
    payload: str  # plan text or shutdown reason
    created_at: float = field(default_factory=time.time)


pending_requests: dict[str, ProtocolState] = {}


def new_request_id() -> str:
    return f"req_{random.randint(0, 999999):06d}"


# 通过 request_id 将响应与原始请求关联起来，验证response_type是否与请求类型匹配
def match_response(response_type: str, request_id: str, approve: bool):
    state = pending_requests.get(request_id)
    if not state:
        print(f"  \033[31m[protocol] unknown request_id: {request_id}\033[0m")
        return
    # 验证响应类型是否匹配请求类型
    if state.type == "shutdown" and response_type != "shutdown_response":
        print(
            f"  \033[31m[protocol] type mismatch: expected shutdown_response, "
            f"got {response_type}\033[0m"
        )
        return
    if state.type == "plan_approval" and response_type != "plan_approval_response":
        print(
            f"  \033[31m[protocol] type mismatch: expected plan_approval_response, "
            f"got {response_type}\033[0m"
        )
        return
    if state.status != "pending":
        print(
            f"  \033[33m[protocol] {request_id} already {state.status}, "
            f"ignoring duplicate\033[0m"
        )
        return
    # 批准或拒绝对应的请求
    state.status = "approved" if approve else "rejected"
    icon = "✓" if approve else "✗"
    color = "32" if approve else "31"
    print(
        f"  \033[{color}m[protocol] {state.type} {icon} "
        f"({request_id}: {state.status})\033[0m"
    )


""" v17+18 自主agent+工作树目录 """

IDLE_POLL_INTERVAL = 5
IDLE_TIMEOUT = 60


# 查找所有依赖项已completed的pending和unwoned任务
def scan_unclaimed_tasks() -> list[dict]:
    unclaimed = []
    for f in sorted(TASKS_DIR.glob("task_*.json")):
        task = json.loads(f.read_text())
        if (
            task.get("status") == "pending"
            and not task.get("owner")
            and can_start(task["id"])
        ):
            unclaimed.append(task)
    return unclaimed


# 每隔IDLE_POLL_INTERVAL秒轮询一次，返回work, shutdown或timeout
def idle_poll(agent_name: str, messages: list, name: str, role: str) -> str:
    """Poll for 60s. Return 'work', 'shutdown', or 'timeout'."""
    for _ in range(IDLE_TIMEOUT // IDLE_POLL_INTERVAL):
        time.sleep(IDLE_POLL_INTERVAL)

        # 检查收件箱——首先发送协议消息
        inbox = BUS.read_inbox(agent_name)
        if inbox:
            # 检查shutdown_request
            for msg in inbox:
                if msg.get("type") == "shutdown_request":
                    req_id = msg.get("metadata", {}).get("request_id", "")
                    BUS.send(
                        name,
                        "lead",
                        "Shutting down gracefully.",
                        "shutdown_response",
                        {"request_id": req_id, "approve": True},
                    )
                    print(
                        f"  \033[35m[protocol] {name} approved shutdown "
                        f"in idle ({req_id})\033[0m"
                    )
                    return "shutdown"
            # 非协议收件箱：注入并恢复工作
            messages.append(
                {"role": "user", "content": "<inbox>" + json.dumps(inbox) + "</inbox>"}
            )
            print(f"  \033[36m[idle] {name} found inbox messages\033[0m")
            return "work"
        # 扫描任务板
        unclaimed = scan_unclaimed_tasks()
        if unclaimed:
            task_data = unclaimed[0]
            result = claim_task(task_data["id"], agent_name)
            if "Claimed" in result:
                wt_info = ""
                if task_data.get("worktree"):
                    wt_path = WORKTREES_DIR / task_data["worktree"]
                    wt_info = f"\nWork directory: {wt_path}"
                messages.append(
                    {
                        "role": "user",
                        "content": f"<auto-claimed>Task {task_data['id']}: "
                        f"{task_data['subject']}{wt_info}</auto-claimed>",
                    }
                )
                print(
                    f"  \033[32m[idle] {name} auto-claimed: "
                    f"{task_data['subject']}\033[0m"
                )
                return "work"
            print(f"  \033[33m[idle] {name} claim failed: " f"{result}\033[0m")

    print(f"  \033[31m[idle] {name} timeout ({IDLE_TIMEOUT}s)\033[0m")
    return "timeout"


""" v15-18 teammate thread """


def consume_lead_inbox(route_protocol: bool = True) -> list[dict]:
    """阅读lead的收件箱。路由协议响应，返回所有消息。
    由 run_check_inbox() 和main loop调用以避免
    消息在没有协议路由的情况下被消费。"""
    msgs = BUS.read_inbox("lead")
    if not msgs:
        return []
    if route_protocol:
        for msg in msgs:
            meta = msg.get("metadata", {})
            req_id = meta.get("request_id", "")
            msg_type = msg.get("type", "")
            if req_id and msg_type.endswith("_response"):
                approve = meta.get("approve", False)
                match_response(msg_type, req_id, approve)
    return msgs


def spawn_teammate_thread(name: str, role: str, prompt: str) -> str:
    """在后台线程中生成一个teammate agent。
    使用空闲循环：在每个llm轮次之后，等待收件箱消息
    （shutdown_request，新任务）而不是退出。"""
    if name in active_teammates:
        return f"Teammate '{name}' already exists"

    system = (
        f"You are '{name}', a {role}. "
        f"Use tools to complete tasks. "
        f"You can list and claim tasks from the board. "
        f"If a task has a worktree, work in that directory."
    )

    def handle_inbox_message(name: str, msg: dict, messages: list) -> bool:
        """按类型分发传入的协议消息。
        如果队友应该停止，则返回 True。"""
        msg_type = msg.get("type", "message")
        meta = msg.get("metadata", {})
        req_id = meta.get("request_id", "")

        if msg_type == "shutdown_request":
            BUS.send(
                name,
                "lead",
                "Shutting down gracefully.",
                "shutdown_response",
                {"request_id": req_id, "approve": True},
            )
            print(
                f"  \033[35m[protocol] {name} approved shutdown " f"({req_id})\033[0m"
            )
            return True  # 停止循环

        if msg_type == "plan_approval_response":
            approve = meta.get("approve", False)
            if approve:
                messages.append(
                    {
                        "role": "user",
                        "content": "[Plan approved] Proceed with the task.",
                    }
                )
            else:
                messages.append(
                    {
                        "role": "user",
                        "content": f"[Plan rejected] Feedback: {msg['content']}",
                    }
                )
        return False  # continue

    def run():
        wt_ctx = {"path": None}

        def _wt_cwd() -> Path | None:
            p = wt_ctx["path"]
            return Path(p) if p else None

        def _run_bash(command: str) -> str:
            return run_bash(command, cwd=_wt_cwd())

        def _run_read(path: str) -> str:
            return run_read(path, cwd=_wt_cwd())

        def _run_write(path: str, content: str) -> str:
            return run_write(path, content, cwd=_wt_cwd())

        def _run_list_tasks():
            tasks = list_tasks()
            if not tasks:
                return "No tasks."
            return "\n".join(
                f"  {t.id}: {t.subject} [{t.status}]"
                + (f" (wt:{t.worktree})" if t.worktree else "")
                for t in tasks
            )

        def _run_claim_task(task_id: str):
            result = claim_task(task_id, owner=name)
            if "Claimed" in result:
                # 如果任务有worktree字段，则设置工作树目录
                task = load_task(task_id)
                if task.worktree:
                    wt_ctx["path"] = str(WORKTREES_DIR / task.worktree)
                else:
                    wt_ctx["path"] = None
            return result

        def _run_complete_task(task_id: str):
            result = complete_task(task_id)
            wt_ctx["path"] = None
            return result

        messages = [{"role": "user", "content": prompt}]
        sub_tools = [
            {
                "name": "bash",
                "description": "Run a shell command.",
                "input_schema": {
                    "type": "object",
                    "properties": {"command": {"type": "string"}},
                    "required": ["command"],
                },
            },
            {
                "name": "read_file",
                "description": "Read file.",
                "input_schema": {
                    "type": "object",
                    "properties": {"path": {"type": "string"}},
                    "required": ["path"],
                },
            },
            {
                "name": "write_file",
                "description": "Write file.",
                "input_schema": {
                    "type": "object",
                    "properties": {
                        "path": {"type": "string"},
                        "content": {"type": "string"},
                    },
                    "required": ["path", "content"],
                },
            },
            {
                "name": "send_message",
                "description": "Send message to another agent.",
                "input_schema": {
                    "type": "object",
                    "properties": {
                        "to": {"type": "string"},
                        "content": {"type": "string"},
                    },
                    "required": ["to", "content"],
                },
            },
            {
                "name": "submit_plan",
                "description": "Submit a plan for Lead approval.",
                "input_schema": {
                    "type": "object",
                    "properties": {"plan": {"type": "string"}},
                    "required": ["plan"],
                },
            },
            # v17 队友列出，申领与完成任务
            {
                "name": "list_tasks",
                "description": "List all tasks on the board.",
                "input_schema": {"type": "object", "properties": {}, "required": []},
            },
            {
                "name": "claim_task",
                "description": "Claim a pending task.",
                "input_schema": {
                    "type": "object",
                    "properties": {"task_id": {"type": "string"}},
                    "required": ["task_id"],
                },
            },
            {
                "name": "complete_task",
                "description": "Mark an in-progress task as completed.",
                "input_schema": {
                    "type": "object",
                    "properties": {"task_id": {"type": "string"}},
                    "required": ["task_id"],
                },
            },
        ]

        sub_handlers = {
            "bash": _run_bash,
            "read_file": _run_read,
            "write_file": _run_write,
            # lambda定义一个接受两个参数的匿名函数，返回值为字符串"Sent"
            "send_message": lambda to, content: (BUS.send(name, to, content), "Sent")[
                1
            ],
            "submit_plan": lambda plan: _teammate_submit_plan(name, plan),
            "list_tasks": _run_list_tasks,
            "claim_task": _run_claim_task,
            "complete_task": _run_complete_task,
        }

        # 外循环：WORK → IDLE循环
        while True:
            # 当上下文被初始化或重置时重新注入身份
            if len(messages) <= 3:
                messages.insert(
                    0,
                    {
                        "role": "user",
                        "content": f"<identity>You are '{name}', role: {role}. "
                        f"Continue your work.</identity>",
                    },
                )

            # WORK phase
            should_shutdown = False
            for _ in range(10):
                inbox = BUS.read_inbox(name)
                for msg in inbox:
                    stopped = handle_inbox_message(name, msg, messages)
                    if stopped:
                        should_shutdown = True
                        break
                if should_shutdown:
                    break
                if inbox and not should_shutdown:
                    non_protocol = [m for m in inbox if m.get("type") == "message"]
                    if non_protocol:
                        messages.append(
                            {
                                "role": "user",
                                "content": f"<inbox>{json.dumps(non_protocol)}</inbox>",
                            }
                        )

                try:
                    response = client.messages.create(
                        model=PRIMARY_MODEL,
                        system=system,
                        messages=messages[-20:],
                        tools=sub_tools,
                        max_tokens=8000,
                    )
                except Exception:
                    break
                messages.append({"role": "assistant", "content": response.content})
                if response.stop_reason != "tool_use":
                    break
                results = []
                for block in response.content:
                    if block.type == "tool_use":
                        handler = sub_handlers.get(block.name)
                        output = handler(**block.input) if handler else "Unknown"
                        results.append(
                            {
                                "type": "tool_result",
                                "tool_use_id": block.id,
                                "content": str(output),
                            }
                        )
                messages.append({"role": "user", "content": results})

            if should_shutdown:
                break

            # IDLE phase
            idle_result = idle_poll(name, messages, role)
            if idle_result == "shutdown":
                break
            if idle_result == "timeout":
                break

            # 执行工具调用
            results = []
            for block in response.content:
                if block.type == "tool_use":
                    handler = sub_handlers.get(block.name)
                    output = handler(**block.input) if handler else "Unknown"
                    results.append(
                        {
                            "type": "tool_result",
                            "tool_use_id": block.id,
                            "content": str(output),
                        }
                    )
            messages.append({"role": "user", "content": results})

        # 发送最终摘要给lead
        summary = "Done."
        for msg in reversed(messages):
            if msg["role"] == "assistant" and isinstance(msg["content"], list):
                for b in msg["content"]:
                    if getattr(b, "type", None) == "text":
                        summary = b.text
                        break
                else:
                    continue
                break
        BUS.send(name, "lead", summary, "result")
        active_teammates.pop(name, None)
        print(f"  \033[32m[teammate] {name} finished\033[0m")

    active_teammates[name] = True
    threading.Thread(target=run, daemon=True).start()
    print(f"  \033[36m[teammate] {name} spawned as {role}\033[0m")
    return f"Teammate '{name}' spawned as {role} (autonomous)"


def _teammate_submit_plan(from_name: str, plan: str) -> str:
    """teammate向lead提交计划以供审批。
    注意：这是一个协议级别的请求，而非代码级别的门禁。
    提交后，团队成员的线程会继续运行——它仍然可以
    调用 bash/write 等函数。真正的强制执行依赖于模型
    等待审批响应后再采取行动。代码级别的工具
    门禁需要阻塞团队成员的工具调度，直到
    收到审批为止。
    """
    req_id = new_request_id()
    pending_requests[req_id] = ProtocolState(
        request_id=req_id,
        type="plan_approval",
        sender=from_name,
        target="lead",
        status="pending",
        payload=plan,
    )
    BUS.send(from_name, "lead", plan, "plan_approval_request", {"request_id": req_id})
    return f"Plan submitted ({req_id}). Waiting for approval..."


""" v16 lead协议工具 """


# lead向teammate发送shutdown_request
def run_request_shutdown(teammate: str) -> str:
    req_id = new_request_id()
    pending_requests[req_id] = ProtocolState(
        request_id=req_id,
        type="shutdown",
        sender="lead",
        target=teammate,
        status="pending",
        payload="",
    )
    BUS.send(
        "lead",
        teammate,
        "Please shut down gracefully.",
        "shutdown_request",
        {"request_id": req_id},
    )
    print(f"  \033[35m[protocol] shutdown_request → {teammate} " f"({req_id})\033[0m")
    return f"Shutdown request sent to {teammate} (req: {req_id})"


# lead要求teammate提交任务计划
def run_request_plan(teammate: str, task: str) -> str:
    BUS.send("lead", teammate, f"Please submit a plan for: {task}", "message")
    return f"Asked {teammate} to submit a plan"


# lead向计划发送者发送批准计划或拒绝计划的响应
def run_review_plan(request_id: str, approve: bool, feedback: str = "") -> str:
    state = pending_requests.get(request_id)
    if not state:
        return f"Request {request_id} not found"
    if state.status != "pending":
        return f"Request {request_id} already {state.status}"
    state.status = "approved" if approve else "rejected"
    BUS.send(
        "lead",
        state.sender,
        feedback or ("Approved" if approve else "Rejected"),
        "plan_approval_response",
        {"request_id": request_id, "approve": approve},
    )
    icon = "✓" if approve else "✗"
    print(f"  \033[32m[protocol] plan {icon} ({request_id})\033[0m")
    return f"Plan {'approved' if approve else 'rejected'} ({request_id})"


""" 
v15 agent team工具
v16 lead其他工具
"""


def run_spawn_teammate(name: str, role: str, prompt: str) -> str:
    return spawn_teammate_thread(name, role, prompt)


def run_send_message(to: str, content: str) -> str:
    BUS.send("lead", to, content)
    return f"Sent to {to}"


# 检查lead的收件箱，通过 match_response 路由协议响应
def run_check_inbox() -> str:
    msgs = consume_lead_inbox(route_protocol=True)
    if not msgs:
        return "(inbox empty)"
    lines = []
    for m in msgs:
        meta = m.get("metadata", {})
        req_id = meta.get("request_id", "")
        tag = f" [{m['type']} req:{req_id}]" if req_id else f" [{m['type']}]"
        lines.append(f"  [{m['from']}]{tag} {m['content'][:200]}")
    return "\n".join(lines)


""" v18 lead worktree工具 """


def run_create_worktree(name: str, task_id: str = "") -> str:
    return create_worktree(name, task_id)


def run_remove_worktree(name: str, discard_changes: bool = False) -> str:
    return remove_worktree(name, discard_changes)


def run_keep_worktree(name: str) -> str:
    return keep_worktree(name)


""" v14 cron """


@dataclass
class CronJob:
    id: str
    cron: str  # "0 9 * * *"
    prompt: str  # message to inject when fired
    recurring: bool  # True = recurring, False = one-shot
    durable: bool  # True = persist to disk


scheduled_jobs: dict[str, CronJob] = {}
cron_queue: list[CronJob] = []
cron_lock = threading.Lock()
_last_fired: dict[str, str] = {}  # job_id → "YYYY-MM-DD HH:MM"


# 运行时匹配，将单个 cron 字段与值进行匹配
def _cron_field_matches(field: str, value: int) -> bool:
    # 匹配任意值
    if field == "*":
        return True
    if field.startswith("*/"):
        # 取出 "/" 后面的数字
        step = int(field[2:])
        return step > 0 and value % step == 0
    # 枚举列表，只要value匹配其中任意一项即可
    if "," in field:
        return any(_cron_field_matches(f.strip(), value) for f in field.split(","))
    # 判断value是否在区间中
    if "-" in field:
        lo, hi = field.split("-", 1)
        return int(lo) <= value <= int(hi)
    return value == int(field)


# 检查分钟/小时/天/月/星期5个字段的cron表达式是否与给定的日期时间匹配
def cron_matches(cron_expr: str, dt: datetime) -> bool:
    fields = cron_expr.strip().split()
    if len(fields) != 5:
        return False
    minute, hour, dom, month, dow = fields
    # Python Monday=0 → cron Sunday=0
    dow_val = (dt.weekday() + 1) % 7

    m = _cron_field_matches(minute, dt.minute)
    h = _cron_field_matches(hour, dt.hour)
    dom_ok = _cron_field_matches(dom, dt.day)
    month_ok = _cron_field_matches(month, dt.month)
    dow_ok = _cron_field_matches(dow, dow_val)

    # 分钟、小时、月份必须全部匹配
    if not (m and h and month_ok):
        return False
    dom_unconstrained = dom == "*"
    dow_unconstrained = dow == "*"
    if dom_unconstrained and dow_unconstrained:
        return True
    # 只有天受约束 → 只检查天
    if dom_unconstrained:
        return dow_ok
    # 只有星期受约束 → 只检查星期
    if dow_unconstrained:
        return dom_ok
    # 两者都受约束 → 任一匹配就触发
    return dom_ok or dow_ok


# 注册时校验，验证单个 cron 字段值在 [low, high] 范围内
def _validate_cron_field(field: str, lo: int, hi: int) -> str | None:
    if field == "*":
        return None
    if field.startswith("*/"):
        step_str = field[2:]
        if not step_str.isdigit():
            return f"Invalid step: {field}"
        step = int(step_str)
        if step <= 0:
            return f"Step must be > 0: {field}"
        return None
    if "," in field:
        for part in field.split(","):
            err = _validate_cron_field(part.strip(), lo, hi)
            if err:
                return err
        return None
    if "-" in field:
        parts = field.split("-", 1)
        if not parts[0].isdigit() or not parts[1].isdigit():
            return f"Invalid range: {field}"
        a, b = int(parts[0]), int(parts[1])
        if a < lo or a > hi or b < lo or b > hi:
            return f"Range {field} out of bounds [{lo}-{hi}]"
        if a > b:
            return f"Range start > end: {field}"
        return None
    if not field.isdigit():
        return f"Invalid field: {field}"
    val = int(field)
    if val < lo or val > hi:
        return f"Value {val} out of bounds [{lo}-{hi}]"
    return None


# 验证 cron 表达式。返回错误消息或None
def validate_cron(cron_expr: str) -> str | None:
    fields = cron_expr.strip().split()
    if len(fields) != 5:
        return f"Expected 5 fields, got {len(fields)}"
    bounds = [(0, 59), (0, 23), (1, 31), (1, 12), (0, 6)]
    names = ["minute", "hour", "day-of-month", "month", "day-of-week"]
    for i, (field, (lo, hi), name) in enumerate(zip(fields, bounds, names)):
        err = _validate_cron_field(field, lo, hi)
        if err:
            return f"{name}: {err}"
    return None


# 将持久作业保留到.scheduled_tasks.json.中
def save_durable_jobs():
    durable = [asdict(j) for j in scheduled_jobs.values() if j.durable]
    DURABLE_PATH.write_text(json.dumps(durable, indent=2))


# 启动时从磁盘加载持久作业
def load_durable_jobs():
    if not DURABLE_PATH.exists():
        return
    try:
        jobs = json.loads(DURABLE_PATH.read_text())
        loaded = 0
        for j in jobs:
            job = CronJob(**j)
            err = validate_cron(job.cron)
            if err:
                print(f"  \033[31m[cron] skipping invalid job {job.id}: {err}\033[0m")
                continue
            scheduled_jobs[job.id] = job
            loaded += 1
        if loaded:
            print(f"  \033[35m[cron] loaded {loaded} durable job(s)\033[0m")
    except Exception:
        pass


# 注册一个新的 cron 作业。返回 CronJob 或错误字符串
def schedule_job(
    cron: str, prompt: str, recurring: bool = True, durable: bool = True
) -> CronJob | str:
    err = validate_cron(cron)
    if err:
        return err
    job = CronJob(
        id=f"cron_{random.randint(0, 999999):06d}",
        cron=cron,
        prompt=prompt,
        recurring=recurring,
        durable=durable,
    )
    with cron_lock:
        scheduled_jobs[job.id] = job
    if durable:
        save_durable_jobs()
    print(f"  \033[35m[cron register] {job.id} '{cron}' → {prompt[:40]}\033[0m")
    return job


# 取消 cron 作业
def cancel_job(job_id: str) -> str:
    with cron_lock:
        job = scheduled_jobs.pop(job_id, None)
    if not job:
        return f"Job {job_id} not found"
    if job.durable:
        # 将修改后的scheduled_jobs写回json文件中
        save_durable_jobs()
    print(f"  \033[31m[cron cancel] {job_id}\033[0m")
    return f"Cancelled {job_id}"


# 独立守护线程：每1秒轮询一次，触发匹配的cron作业
def cron_scheduler_loop():
    while True:
        time.sleep(1)
        now = datetime.now()
        # 日期感知标记可防止日常作业在第 2 天以上跳过
        minute_marker = now.strftime("%Y-%m-%d %H:%M")
        with cron_lock:
            for job in list(scheduled_jobs.values()):
                try:
                    # 分钟级去重检查
                    if cron_matches(job.cron, now):
                        if _last_fired.get(job.id) != minute_marker:
                            # 添加到主线程待办作业队列
                            cron_queue.append(job)
                            _last_fired[job.id] = minute_marker
                            print(
                                f"  \033[35m[cron fire] {job.id} → "
                                f"{job.prompt[:40]}\033[0m"
                            )
                        if not job.recurring:
                            scheduled_jobs.pop(job.id, None)
                            if job.durable:
                                save_durable_jobs()
                # 捕获单个cron作业错误，防止单个作业异常杀死整个调度程序线程
                except Exception as e:
                    print(f"  \033[31m[cron error] {job.id}: {e}\033[0m")


# 消费cron_queue中已触发的作业（由agent_loop调用）
def consume_cron_queue() -> list[CronJob]:
    with cron_lock:
        fired = list(cron_queue)
        cron_queue.clear()
    return fired


# 检查cron_queue中是否有已触发的作业等待交付
def has_cron_queue() -> bool:
    with cron_lock:
        return bool(cron_queue)


# 在启动时加载持久作业，然后启动调度程序线程
load_durable_jobs()
threading.Thread(target=cron_scheduler_loop, daemon=True).start()
print("  \033[35m[cron] scheduler thread started\033[0m")


""" v12 任务系统 """


# 装饰器，自动为类生成样板方法
@dataclass
class Task:
    id: str
    subject: str
    description: str
    status: str
    owner: str | None
    blockedBy: list[str]
    worktree: str | None = None  # s18: bound worktree name


def _task_path(task_id: str) -> Path:
    return TASKS_DIR / f"{task_id}.json"


def create_task(
    subject: str, description: str = "", blockedBy: list[str] | None = None
) -> Task:
    task = Task(
        id=f"task_{int(time.time())}_{random.randint(0, 9999):04d}",
        subject=subject,
        description=description,
        status="pending",
        owner=None,
        blockedBy=blockedBy or [],
    )
    save_task(task)
    return task


# 保存任务到文件
def save_task(task: Task):
    _task_path(task.id).write_text(json.dumps(asdict(task), indent=2))


# 从文件中加载任务
def load_task(task_id: str) -> Task:
    return Task(**json.loads(_task_path(task_id).read_text()))


#
def list_tasks() -> list[Task]:
    return [
        # json -> dict -> 关键词参数
        Task(**json.loads(p.read_text()))
        for p in sorted(TASKS_DIR.glob("task_*.json"))
    ]


# 以 JSON 形式返回完整的任务详细信息
def get_task(task_id: str) -> str:
    task = load_task(task_id)
    # task实例 -> dict -> 格式化的json字符串
    return json.dumps(asdict(task), indent=2)


# 检查所有blockedBy dependencies是否已完成。缺少的dependencies将被视为锁定。
def can_start(task_id: str) -> bool:
    task = load_task(task_id)
    for dep_id in task.blockedBy:
        # 前置任务不存在
        if not _task_path(dep_id).exists():
            return False
        # 前置任务存在但未完成
        if load_task(dep_id).status != "completed":
            return False
    return True


# 申领pending任务，更新任务领取者与状态
def claim_task(task_id: str, owner: str = "agent") -> str:
    task = load_task(task_id)
    if task.status != "pending":
        return f"Task {task_id} is {task.status}, cannot claim"
    if task.owner:
        return f"Task {task_id} already owned by {task.owner}"
    if not can_start(task_id):
        deps = [
            d
            for d in task.blockedBy
            if _task_path(d).exists() and load_task(d).status != "completed"
        ]
        missing = [d for d in task.blockedBy if not _task_path(d).exists()]
        parts = []
        if deps:
            parts.append(f"blocked by: {deps}")
        if missing:
            parts.append(f"missing deps: {missing}")
        return "Cannot start — " + ", ".join(parts)
    task.owner = owner
    task.status = "in_progress"
    save_task(task)
    print(f"  \033[36m[claim] {task.subject} → in_progress\033[0m")
    return f"Claimed {task.id} ({task.subject})"


# 更新in_progress任务状态为completed，解锁后续任务
def complete_task(task_id: str) -> str:
    task = load_task(task_id)
    if task.status != "in_progress":
        return f"Task {task_id} is {task.status}, cannot complete"
    task.status = "completed"
    save_task(task)
    unblocked = [
        t.subject
        for t in list_tasks()
        if t.status == "pending" and t.blockedBy and can_start(t.id)
    ]
    print(f"  \033[32m[complete] {task.subject} ✓\033[0m")
    msg = f"Completed {task.id} ({task.subject})"
    if unblocked:
        msg += f"\nUnblocked: {', '.join(unblocked)}"
        print(f"  \033[33m[unblocked] {', '.join(unblocked)}\033[0m")
    return msg


""" v10 Prompt Sections """

PROMPT_SECTIONS = {
    "identity": "You are a coding agent. Act, don't explain.",
    "tools": "Available tools: bash, read_file, write_file.",
    "workspace": f"Working directory: {WORKDIR}",
    "skills": "Skills available:\n{catalog}\nUse load_skill to get full details when needed.",
    "memory": "Relevant memories are injected below when available:\n{memories}",
    "instructions": "Respect user preferences from memory. When the user says 'remember' or expresses a clear preference, extract it as a memory.",
}


# 基于当前上下文选择与合并prompt sections
def assemble_system_prompt(context: dict) -> str:
    sections = []

    # 总是加载
    sections.append(PROMPT_SECTIONS["identity"])
    sections.append(PROMPT_SECTIONS["tools"])
    sections.append(PROMPT_SECTIONS["workspace"])

    # Skills catalog 替代旧的 list_skills()
    skills = context.get("skills_catalog", "")
    if skills and skills != "(no skills found)":
        sections.append(PROMPT_SECTIONS["skills"].format(catalog=skills))

    # 当MEMORY.md存在且有内容时才加载记忆 + 行为指令
    memories = context.get("memories", "")
    if memories:
        sections.append(PROMPT_SECTIONS["memory"].format(memories=memories))
        sections.append(PROMPT_SECTIONS["instructions"])

    return "\n\n".join(sections)







""" v9 记忆系统 """

MEMORY_TYPES = ["user", "feedback", "project", "reference"]


def _memory_parse_frontmatter(text: str) -> tuple[dict, str]:
    if not text.startswith("---"):
        return {}, text
    parts = text.split("---", 2)
    if len(parts) < 3:
        return {}, text
    meta = {}
    for line in parts[1].strip().splitlines():
        # 只处理有冒号的行
        if ":" in line:
            # 按第一个冒号分割
            k, v = line.split(":", 1)
            meta[k.strip()] = v.strip().strip('"').strip("'")
    return meta, parts[2].strip()


# 将记忆写入文档
def write_memory_file(name: str, mem_type: str, description: str, body: str):
    slug = name.lower().replace(" ", "-").replace("/", "-")
    filename = f"{slug}.md"
    filepath = MEMORY_DIR / filename
    filepath.write_text(
        f"---\nname: {name}\ndescription: {description}\ntype: {mem_type}\n---\n\n{body}\n"
    )
    # 重建索引
    _rebuild_index()
    return filepath


# 从所有记忆文档中重建MEMORY.md索引
def _rebuild_index():
    lines = []
    for f in sorted(MEMORY_DIR.glob("*.md")):
        if f.name == "MEMORY.md":
            continue
        raw = f.read_text()
        meta, body = _memory_parse_frontmatter(raw)
        name = meta.get("name", f.stem)
        desc = meta.get("description", body.split("\n")[0][:80])
        lines.append(f"- [{name}]({f.name}) — {desc}")
    MEMORY_INDEX.write_text("\n".join(lines) + "\n" if lines else "")


# 阅读MEMORY.md索引（每轮注入到系统提示词中）
def read_memory_index() -> str:
    if not MEMORY_INDEX.exists():
        return ""
    text = MEMORY_INDEX.read_text().strip()
    return text if text else ""


# 阅读记忆文档全文
def read_memory_file(filename: str) -> str | None:
    path = MEMORY_DIR / filename
    if not path.exists():
        return None
    return path.read_text()


# 列出所有记忆文档的元数据
def list_memory_files() -> list[dict]:
    result = []
    for f in sorted(MEMORY_DIR.glob("*.md")):
        if f.name == "MEMORY.md":
            continue
        raw = f.read_text()
        meta, body = _memory_parse_frontmatter(raw)
        result.append(
            {
                "filename": f.name,
                # f.stem返回不带拓展名的文件名
                "name": meta.get("name", f.stem),
                "description": meta.get("description", ""),
                "type": meta.get("type", "user"),
                "body": body,
            }
        )
    return result


# 通过将最近的对话与记忆名称/描述进行匹配，选择相关的记忆文件名
# 使用简单的llm调用或回退到名字+描述的关键词匹配
def select_relevant_memories(messages: list, max_items: int = 5) -> list[str]:
    files = list_memory_files()
    if not files:
        return []

    # 收集用户最近的文本以获取上下文信息
    recent_texts = []
    for msg in reversed(messages):
        if msg.get("role") == "user":
            content = msg.get("content", "")
            # 处理工具结果
            if isinstance(content, list):
                content = " ".join(
                    # 推导式语法：<输出表达式>  for <变量> in <可迭代对象>  if <过滤条件>
                    # 此处的意思是遍历content，保留其中type为text的块，并提取text的属性
                    str(getattr(b, "text", ""))
                    for b in content
                    if getattr(b, "type", None) == "text"
                )
            # 处理普通提问
            if isinstance(content, str):
                recent_texts.append(content)
            if len(recent_texts) >= 3:
                break
    recent = " ".join(reversed(recent_texts))[:2000]

    if not recent.strip():
        return []

    # 构建名称+描述的目录，以供llm选用
    catalog_lines = []
    for i, f in enumerate(files):
        catalog_lines.append(f"{i}: {f['name']} — {f['description']}")
    catalog = "\n".join(catalog_lines)

    prompt = (
        "Given the recent conversation and the memory catalog below, "
        "select the indices of memories that are clearly relevant. "
        "Return ONLY a JSON array of integers, e.g. [0, 3]. "
        "If none are relevant, return [].\n\n"
        f"Recent conversation:\n{recent}\n\n"
        f"Memory catalog:\n{catalog}"
    )

    try:
        response = client.messages.create(
            model=PRIMARY_MODEL,
            messages=[{"role": "user", "content": prompt}],
            max_tokens=200,
        )
        text = extract_text(response.content).strip()
        # 用正则 \[.*?\] 在 llm 回复中匹配第一个 [...] 形式的数组
        match = re.search(r"\[.*?\]", text, re.DOTALL)
        if match:
            # 将json数组解析成python列表
            indices = json.loads(match.group())
            selected = []
            for idx in indices:
                if isinstance(idx, int) and 0 <= idx < len(files):
                    # 获取对应记忆文档名
                    selected.append(files[idx]["filename"])
                    if len(selected) >= max_items:
                        break
            return selected
    except Exception:
        pass

    # 切分最近用户文本以获取词集
    keywords = [w.lower() for w in recent.split() if len(w) > 3]
    selected = []
    for f in files:
        text = (f["name"] + " " + f["description"]).lower()
        # 如果有关键词在名称或描述中
        if any(kw in text for kw in keywords):
            selected.append(f["filename"])
            if len(selected) >= max_items:
                break
    return selected


# 读取相关的具体记忆内容并注入到上下文中
def load_memories(messages: list) -> str:
    selected_files = select_relevant_memories(messages)
    if not selected_files:
        return ""

    parts = ["<relevant_memories>"]
    for filename in selected_files:
        content = read_memory_file(filename)
        if content:
            parts.append(content)
    parts.append("</relevant_memories>")
    return "\n\n".join(parts)


# 在每个轮次之后从最近对话里提取新的记忆
def extract_memories(messages: list):
    # 收集最近对话文本
    dialogue_parts = []
    for msg in messages[-10:]:
        role = msg.get("role", "?")
        content = msg.get("content", "")
        if isinstance(content, list):
            content = " ".join(
                str(getattr(b, "text", ""))
                for b in content
                if getattr(b, "type", None) == "text"
            )
        if isinstance(content, str) and content.strip():
            dialogue_parts.append(f"{role}: {content}")
    dialogue = "\n".join(dialogue_parts)

    if not dialogue.strip():
        return

    # 检查现有记忆，防止重复
    existing = list_memory_files()
    existing_desc = (
        "\n".join(f"- {m['name']}: {m['description']}" for m in existing)
        if existing
        else "(none)"
    )

    prompt = (
        "Extract user preferences, constraints, or project facts from this dialogue.\n"
        "Return a JSON array. Each item: {name, type, description, body}.\n"
        "- name: short kebab-case identifier (e.g. 'user-preference-tabs')\n"
        "- type: one of 'user' (user preference), 'feedback' (guidance), "
        "'project' (project fact), 'reference' (external pointer)\n"
        "- description: one-line summary for index lookup\n"
        "- body: full detail in markdown\n"
        "If nothing new or already covered by existing memories, return [].\n\n"
        f"Existing memories:\n{existing_desc}\n\n"
        f"Dialogue:\n{dialogue[:4000]}"
    )

    try:
        response = client.messages.create(
            model=PRIMARY_MODEL,
            messages=[{"role": "user", "content": prompt}],
            max_tokens=800,
        )
        text = extract_text(response.content).strip()
        # 从response中提取json数组
        match = re.search(r"\[.*\]", text, re.DOTALL)
        if not match:
            return
        # 转换成python列表
        items = json.loads(match.group())
        if not items:
            return
        count = 0
        for mem in items:
            name = mem.get("name", f"memory_{int(time.time())}")
            mem_type = mem.get("type", "user")
            desc = mem.get("description", "")
            body = mem.get("body", "")
            if desc and body:
                write_memory_file(name, mem_type, desc, body)
                count += 1
        if count:
            print(f"\n\033[33m[Memory: extracted {count} new memories]\033[0m")
    except Exception:
        pass


# 合并阈值
CONSOLIDATE_THRESHOLD = 10


# 合并重复/过期的记忆，当文件数量≥阈值时触发
def consolidate_memories():
    files = list_memory_files()
    if len(files) < CONSOLIDATE_THRESHOLD:
        return

    catalog = "\n\n".join(
        f"## {f['filename']}\nname: {f['name']}\ndescription: {f['description']}\n{f['body']}"
        for f in files
    )

    prompt = (
        "Consolidate the following memory files. Rules:\n"
        "1. Merge duplicates into one\n"
        "2. Remove outdated/contradicted memories\n"
        "3. Keep the total under 30 memories\n"
        "4. Preserve important user preferences above all\n"
        "Return a JSON array. Each item: {name, type, description, body}.\n\n"
        f"{catalog[:16000]}"
    )

    try:
        response = client.messages.create(
            model=PRIMARY_MODEL,
            messages=[{"role": "user", "content": prompt}],
            max_tokens=3000,
        )
        text = extract_text(response.content).strip()
        match = re.search(r"\[.*\]", text, re.DOTALL)
        if not match:
            return
        items = json.loads(match.group())

        # 删除旧记忆文档
        for f in MEMORY_DIR.glob("*.md"):
            if f.name != "MEMORY.md":
                f.unlink()

        for mem in items:
            name = mem.get("name", f"memory_{int(time.time())}")
            mem_type = mem.get("type", "user")
            desc = mem.get("description", "")
            body = mem.get("body", "")
            if desc and body:
                write_memory_file(name, mem_type, desc, body)

        print(
            f"\n\033[33m[Memory: consolidated {len(files)} → {len(items)} memories]\033[0m"
        )
    except Exception:
        pass


# v7 扫描skill目录，解析每个SKILL.md的YAML frontmatter，生成技能列表
def _skill_parse_frontmatter(text: str) -> tuple[dict, str]:
    # 如果文本不以 "---" 开头，说明没有 frontmatter，返回空字典和原始文本
    if not text.startswith("---"):
        return {}, text
    # split 分割文本为三部分，最多分割两次，得到前导 "---"、YAML内容、剩余文本
    parts = text.split("---", 2)
    # 如果分割后的部分少于3，说明没有完整的 frontmatter，返回空字典和原始文本
    if len(parts) < 3:
        return {}, text
    try:
        # 使用 yaml.safe_load 解析 YAML 内容
        meta = yaml.safe_load(parts[1]) or {}
    except yaml.YAMLError:
        meta = {}
    # 返回描述和详细内容
    return meta, parts[2].strip()


# v7 启动时构建技能注册表
SKILL_REGISTRY: dict[str, dict] = {}


# 扫描skills，填入SKILL_REGISTRY
def _scan_skills():
    if not SKILLS_DIR.exists():
        return
    # 按字母顺序遍历skills目录下的每个子目录
    for d in sorted(SKILLS_DIR.iterdir()):
        if not d.is_dir():
            continue
        manifest = d / "SKILL.md"
        if manifest.exists():
            raw = manifest.read_text()
            meta, body = _skill_parse_frontmatter(raw)
            name = meta.get("name", d.name)
            desc = meta.get("description", raw.split("\n")[0].lstrip("#").strip())
            SKILL_REGISTRY[name] = {"name": name, "description": desc, "content": raw}


# 模块加载时立刻执行扫描
# 因为SYSTEM是模块级全局变量，它的构建依赖于SKILL_REGISTRY，所以_scan_skills() 也必须在模块级先执行
_scan_skills()


# v7 列出所有技能的名称和一行描述
def list_skills() -> str:
    if not SKILL_REGISTRY:
        return "(no skills found)"
    return "\n".join(
        f"- **{s['name']}**: {s['description']}" for s in SKILL_REGISTRY.values()
    )


# v6 子系统提示词
SUB_SYSTEM = (
    f"You are a coding agent at {WORKDIR}. "
    "Complete the task you were given, then return a concise summary. "
    "Do not delegate further."
)

""" v2-v7 工具实现 """


def safe_path(p: str) -> Path:
    # 拼接与解析路径
    path = (WORKDIR / p).resolve()
    if not path.is_relative_to(WORKDIR):
        raise ValueError(f"Path escapes workspace: {p}")
    return path


def run_bash(command: str) -> str:
    # 危险命令
    dangerous = ["rm -rf /", "sudo", "shutdown", "reboot", "> /dev/"]
    if any(d in command for d in dangerous):
        return "Error: Dangerous command blocked"
    try:
        # 通过子进程执行shell命令
        r = subprocess.run(
            command,
            shell=True,
            cwd=WORKDIR,
            capture_output=True,
            text=True,
            timeout=120,
        )
        #  将标准输出和错误输出合并，去掉首尾空白
        out = (r.stdout + r.stderr).strip()
        return out[:50000] if out else "(no output)"
    except subprocess.TimeoutExpired:
        return "Error: Timeout (120s)"
    except (FileNotFoundError, OSError) as e:
        return f"Error: {e}"


def run_read(path: str, limit: int | None = None) -> str:
    try:
        lines = safe_path(path).read_text().splitlines()
        if limit and limit < len(lines):
            lines = lines[:limit] + [f"... ({len(lines) - limit} more lines)"]
        return "\n".join(lines)
    except Exception as e:
        return f"Error: {e}"


def run_write(path: str, content: str) -> str:
    try:
        file_path = safe_path(path)
        # 递归创建父目录（如果不存在），然后写入内容
        file_path.parent.mkdir(parents=True, exist_ok=True)
        file_path.write_text(content)
        return f"Wrote {len(content)} bytes to {path}"
    except Exception as e:
        return f"Error: {e}"


def run_edit(path: str, old_text: str, new_text: str) -> str:
    try:
        file_path = safe_path(path)
        text = file_path.read_text()
        if old_text not in text:
            return f"Error: text not found in {path}"
        file_path.write_text(text.replace(old_text, new_text, 1))
        return f"Edited {path}"
    except Exception as e:
        return f"Error: {e}"


def run_glob(pattern: str) -> str:
    # 只有调用时才加载模块（懒加载）
    import glob as g

    try:
        results = []
        for p in g.glob(str(WORKDIR / pattern), recursive=True):
            rel_path = Path(p).relative_to(WORKDIR)
            results.append(str(rel_path))
        return "\n".join(results) if results else "(no matches)"
    except Exception as e:
        return f"Error: {e}"


# 将输入的todos规范化为列表形式，并进行基本验证
def _normalize_todos(todos):
    if isinstance(todos, str):
        try:
            # 先尝试使用json.loads解析字符串为Python对象
            todos = json.loads(todos)
        except json.JSONDecodeError:
            try:
                # 再使用ast.literal_eval解析字符串为Python对象，安全性高于eval
                todos = ast.literal_eval(todos)
            except (SyntaxError, ValueError):
                return None, "Error: todos must be a list or JSON array string"
    if not isinstance(todos, list):
        return None, "Error: todos must be a list"
    # i为索引，t为每个todo项
    for i, t in enumerate(todos):
        if not isinstance(t, dict):
            return None, f"Error: todos[{i}] must be an object"
        if "content" not in t or "status" not in t:
            return None, f"Error: todos[{i}] missing 'content' or 'status'"
        if t["status"] not in ("pending", "in_progress", "completed"):
            return None, f"Error: todos[{i}] has invalid status '{t['status']}'"
    return todos, None


def run_todo_write(todos: list) -> str:
    global CURRENT_TODOS
    todos, error = _normalize_todos(todos)
    if error:
        return error
    CURRENT_TODOS = todos
    lines = ["\n\033[33m## Current Tasks\033[0m"]
    for t in CURRENT_TODOS:
        # 字典即建即用模式，定义字典后立刻用[key]取值，不需要给字典起名字
        icon = {
            "pending": " ",
            "in_progress": "\033[36m▸\033[0m",
            "completed": "\033[32m✓\033[0m",
        }[t["status"]]
        lines.append(f"  [{icon}] {t['content']}")
    print("\n".join(lines))
    return f"Updated {len(CURRENT_TODOS)} tasks"


# 从消息内容块中提取文本，忽略非文本块
def extract_text(content) -> str:
    if not isinstance(content, list):
        return str(content)
    return "\n".join(
        getattr(b, "text", "") for b in content if getattr(b, "type", None) == "text"
    )


def spawn_subagent(description: str) -> str:
    print(f"\n\033[35m[Subagent spawned]\033[0m")
    # 子agent的消息上下文仅包含用户的描述
    messages = [{"role": "user", "content": description}]

    # 最多30轮循环，防止无限循环
    for _ in range(30):
        response = client.messages.create(
            model=PRIMARY_MODEL,
            system=SUB_SYSTEM,
            messages=messages,
            tools=SUB_TOOLS,
            max_tokens=8000,
        )
        messages.append({"role": "assistant", "content": response.content})
        if response.stop_reason != "tool_use":
            break
        results = []
        for block in response.content:
            if block.type == "tool_use":
                # 子agent也运行hook，权限检查同样适用
                blocked = trigger_hooks("PreToolUse", block)
                if blocked:
                    results.append(
                        {
                            "type": "tool_result",
                            "tool_use_id": block.id,
                            "content": str(blocked),
                        }
                    )
                    continue
                handler = SUB_HANDLERS.get(block.name)
                output = handler(**block.input) if handler else f"Unknown: {block.name}"
                trigger_hooks("PostToolUse", block, output)
                print(f"  \033[90m[sub] {block.name}: {str(output)[:100]}\033[0m")
                results.append(
                    {"type": "tool_result", "tool_use_id": block.id, "content": output}
                )
        messages.append({"role": "user", "content": results})

    # 如果子agent在30轮循环后仍未给出最终答案，则回溯查找最后一个助手的文本回复作为结果
    result = extract_text(messages[-1]["content"])
    # 从后往前遍历所有消息，找到第一个有文本的助手回复
    if not result:
        for msg in reversed(messages):
            if msg["role"] == "assistant":
                result = extract_text(msg["content"])
                if result:
                    break
        # 整个消息列表里没有任何助手消息包含文本，返回固定报错
        if not result:
            result = "Subagent stopped after 30 turns without final answer."
    print(f"\033[35m[Subagent done]\033[0m")
    # 仅返回最终结论，丢弃整个消息历史
    return result


# v7 加载技能内容，避免路径遍历
def load_skill(name: str) -> str:
    skill = SKILL_REGISTRY.get(name)
    if not skill:
        return f"Skill not found: {name}"
    return skill["content"]


""" v8 四层压缩管道 """

CONTEXT_LIMIT = 50000
KEEP_RECENT_TOOL_RESULTS = 3
# 超过阈值字符的工具结果将被持久化到磁盘中，避免占用上下文
PERSIST_THRESHOLD = 30000


# 估计消息内容长度
def estimate_size(msgs):
    return len(str(msgs))


# 获取消息块的类型
def _block_type(block):
    return (
        block.get("type") if isinstance(block, dict) else getattr(block, "type", None)
    )


# 检查消息是否包含工具调用
def _message_has_tool_use(msg):
    if msg.get("role") != "assistant":
        return False
    content = msg.get("content")
    if not isinstance(content, list):
        return False
    return any(_block_type(block) == "tool_use" for block in content)


# 检查消息是否为工具结果消息
def _is_tool_result_message(msg):
    if msg.get("role") != "user":
        return False
    content = msg.get("content")
    if not isinstance(content, list):
        return False
    return any(
        isinstance(block, dict) and block.get("type") == "tool_result"
        for block in content
    )


# L1 snip compact， 裁掉中间无关的旧对话，保留首尾
def snip_compact(messages, max_messages=50):
    if len(messages) <= max_messages:
        return messages
    keep_head, keep_tail = 3, max_messages - 3
    head_end, tail_start = keep_head, len(messages) - keep_tail
    # 如果首部最后一条消息是工具调用，则继续保留后续的工具结果消息
    if head_end > 0 and _message_has_tool_use(messages[head_end - 1]):
        while head_end < len(messages) and _is_tool_result_message(messages[head_end]):
            head_end += 1
    # 如果尾部第一条消息是工具结果，则继续保留前面的工具调用消息
    # 对于head_end，一次回复可能包含多个工具调用和结果，所以用while遍历
    # 对于tail_start，一个 tool_result 只对应一个 tool_use，用if就够了
    if (
        tail_start > 0
        and tail_start < len(messages)
        and _is_tool_result_message(messages[tail_start])
        and _message_has_tool_use(messages[tail_start - 1])
    ):
        tail_start -= 1
    if head_end >= tail_start:
        return messages
    snipped = tail_start - head_end
    return (
        messages[:head_end]
        + [{"role": "user", "content": f"[snipped {snipped} messages]"}]
        + messages[tail_start:]
    )


# L2 micro compact，旧工具结果占位
# 收集所有工具结果块
def collect_tool_results(messages):
    blocks = []
    for mi, msg in enumerate(messages):
        if msg.get("role") != "user" or not isinstance(msg.get("content"), list):
            continue
        for bi, block in enumerate(msg["content"]):
            if isinstance(block, dict) and block.get("type") == "tool_result":
                blocks.append((mi, bi, block))
    return blocks


# 将旧工具结果替换为占位符
def micro_compact(messages):
    tool_results = collect_tool_results(messages)
    if len(tool_results) <= KEEP_RECENT_TOOL_RESULTS:
        return messages
    # "_,"为丢弃变量，表示该值不需要
    for _, _, block in tool_results[:-KEEP_RECENT_TOOL_RESULTS]:
        if len(block.get("content", "")) > 120:
            block["content"] = "[Earlier tool result compacted. Re-run if needed.]"
    return messages


# L3 tool result budget，保存大型结果到磁盘中
def persist_large_output(tool_use_id, output):
    if len(output) <= PERSIST_THRESHOLD:
        return output
    TOOL_RESULTS_DIR.mkdir(parents=True, exist_ok=True)
    path = TOOL_RESULTS_DIR / f"{tool_use_id}.txt"
    if not path.exists():
        path.write_text(output, encoding="utf-8")
    return f"<persisted-output>\nFull output: {path}\nPreview:\n{output[:2000]}\n</persisted-output>"


def tool_result_budget(messages, max_bytes=200_000):
    last = messages[-1] if messages else None
    if (
        not last
        or last.get("role") != "user"
        or not isinstance(last.get("content"), list)
    ):
        return messages
    blocks = [
        (i, b)
        for i, b in enumerate(last["content"])
        if isinstance(b, dict) and b.get("type") == "tool_result"
    ]
    # 统计最后一条 user 消息里所有 tool_result 的总大小
    total = sum(len(str(b.get("content", ""))) for _, b in blocks)
    if total <= max_bytes:
        return messages
    # 按content长度降序排列blocks，优先处理最大的输出
    ranked = sorted(
        blocks, key=lambda p: len(str(p[1].get("content", ""))), reverse=True
    )
    for _, block in ranked:
        if total <= max_bytes:
            break
        content = str(block.get("content", ""))
        if len(content) <= PERSIST_THRESHOLD:
            continue
        tid = block.get("tool_use_id", "unknown")
        # 将大型输出持久化到磁盘，并替换为占位符
        block["content"] = persist_large_output(tid, content)
        # 重新计算总大小，一旦小于阈值就停止处理
        total = sum(len(str(b.get("content", ""))) for _, b in blocks)
    return messages


# L4 auto compact，llm全量摘要
# 将完整消息写入磁盘，返回文件路径
def write_transcript(messages):
    TRANSCRIPT_DIR.mkdir(parents=True, exist_ok=True)
    path = TRANSCRIPT_DIR / f"transcript_{int(time.time())}.jsonl"
    with path.open("w", encoding="utf-8") as f:
        for msg in messages:
            f.write(json.dumps(msg, default=str) + "\n")
    return path


def summarize_history(messages):
    # 对话开头往往包含用户需求、初始决策等最重要的信息，而末尾多为执行日志，因此保留前段信息比保留后段信息好
    conversation = json.dumps(messages, default=str)[:80000]
    prompt = (
        "Summarize this coding-agent conversation so work can continue.\n"
        "Preserve: 1. current goal, 2. key findings/decisions, 3. files read/changed, "
        "4. remaining work, 5. user constraints.\nBe compact but concrete.\n\n"
        + conversation
    )
    response = client.messages.create(
        model=PRIMARY_MODEL,
        messages=[{"role": "user", "content": prompt}],
        max_tokens=2000,
    )
    return (
        "\n".join(
            # 获取文本块的内容，忽略非文本块的工具调用
            getattr(block, "text", "")
            for block in response.content
            # 如果返回的内容块中没有文本块，则返回固定字符串
            if getattr(block, "type", None) == "text"
        ).strip()
        or "(empty summary)"
    )


# 保存完整对话，返回压缩后的摘要消息
def compact_history(messages):
    transcript_path = write_transcript(messages)
    print(f"[transcript saved: {transcript_path}]")
    summary = summarize_history(messages)
    return [{"role": "user", "content": f"[Compacted]\n\n{summary}"}]


""" v12 task工具 """


def run_create_task(
    subject: str, description: str = "", blockedBy: list[str] | None = None
) -> str:
    task = create_task(subject, description, blockedBy)
    deps = f" (blockedBy: {', '.join(blockedBy)})" if blockedBy else ""
    print(f"  \033[34m[create] {task.subject}{deps}\033[0m")
    return f"Created {task.id}: {task.subject}{deps}"


def run_list_tasks() -> str:
    tasks = list_tasks()
    if not tasks:
        return "No tasks. Use create_task to add some."
    lines = []
    for t in tasks:
        icon = {"pending": "○", "in_progress": "●", "completed": "✓"}.get(t.status, "?")
        deps = f" (blockedBy: {', '.join(t.blockedBy)})" if t.blockedBy else ""
        owner = f" [{t.owner}]" if t.owner else ""
        lines.append(f"  {icon} {t.id}: {t.subject} " f"[{t.status}]{owner}{deps}")
    return "\n".join(lines)


def run_get_task(task_id: str) -> str:
    try:
        return get_task(task_id)
    except FileNotFoundError:
        return f"Error: Task {task_id} not found"


def run_claim_task(task_id: str) -> str:
    return claim_task(task_id, owner="agent")


def run_complete_task(task_id: str) -> str:
    return complete_task(task_id)


""" v14 cron工具 """


def run_schedule_cron(
    cron: str, prompt: str, recurring: bool = True, durable: bool = True
) -> str:
    result = schedule_job(cron, prompt, recurring, durable)
    if isinstance(result, str):
        return f"Error: {result}"
    return f"Scheduled {result.id}: '{cron}' → {prompt}"


def run_list_crons() -> str:
    with cron_lock:
        jobs = list(scheduled_jobs.values())
    if not jobs:
        return "No cron jobs. Use schedule_cron to add one."
    lines = []
    for j in jobs:
        tag = "recurring" if j.recurring else "one-shot"
        dur = "durable" if j.durable else "session"
        lines.append(f"  {j.id}: '{j.cron}' → {j.prompt[:40]} " f"[{tag}, {dur}]")
    return "\n".join(lines)


def run_cancel_cron(job_id: str) -> str:
    return cancel_job(job_id)


""" v16 工具分发 """


# 执行工具调用块，返回输出
def execute_tool(block) -> str:
    handler = {
        "bash": run_bash,
        "read_file": run_read,
        "write_file": run_write,
        "edit_file": run_edit,
        "glob": run_glob,
        "todo_write": run_todo_write,
        "task": spawn_subagent,
        "load_skill": load_skill,
        "create_task": run_create_task,
        "list_tasks": run_list_tasks,
        "get_task": run_get_task,
        "claim_task": run_claim_task,
        "complete_task": run_complete_task,
        "schedule_cron": run_schedule_cron,
        "list_crons": run_list_crons,
        "cancel_cron": run_cancel_cron,
        "spawn_teammate": run_spawn_teammate,
        "send_message": run_send_message,
        "check_inbox": run_check_inbox,
        "request_shutdown": run_request_shutdown,
        "request_plan": run_request_plan,
        "review_plan": run_review_plan,
    }.get(block.name)
    if handler:
        return handler(**block.input)
    return f"Unknown tool: {block.name}"


""" v14 cron调度程序 """

""" v2-v12 工具定义与分发映射 """

TOOLS = [
    {
        "name": "bash",
        "description": "Run a shell command.",
        "input_schema": {
            "type": "object",
            "properties": {"command": {"type": "string"}},
            "required": ["command"],
        },
    },
    {
        "name": "read_file",
        "description": "Read file contents.",
        "input_schema": {
            "type": "object",
            "properties": {"path": {"type": "string"}, "limit": {"type": "integer"}},
            "required": ["path"],
        },
    },
    {
        "name": "write_file",
        "description": "Write content to a file.",
        "input_schema": {
            "type": "object",
            "properties": {"path": {"type": "string"}, "content": {"type": "string"}},
            "required": ["path", "content"],
        },
    },
    {
        "name": "edit_file",
        "description": "Replace exact text in a file once.",
        "input_schema": {
            "type": "object",
            "properties": {
                "path": {"type": "string"},
                "old_text": {"type": "string"},
                "new_text": {"type": "string"},
            },
            "required": ["path", "old_text", "new_text"],
        },
    },
    {
        "name": "glob",
        "description": "Find files matching a glob pattern.",
        "input_schema": {
            "type": "object",
            "properties": {"pattern": {"type": "string"}},
            "required": ["pattern"],
        },
    },
    # v5 todo工具
    {
        "name": "todo_write",
        "description": "Create and manage a task list for your current coding session.",
        "input_schema": {
            "type": "object",
            "properties": {
                "todos": {
                    "type": "array",
                    "items": {
                        "type": "object",
                        "properties": {
                            "content": {"type": "string"},
                            "status": {
                                "type": "string",
                                "enum": ["pending", "in_progress", "completed"],
                            },
                        },
                        "required": ["content", "status"],
                    },
                }
            },
            "required": ["todos"],
        },
    },
    # v6 task工具
    {
        "name": "task",
        "description": "Launch a subagent to handle a complex subtask. Returns only the final conclusion.",
        "input_schema": {
            "type": "object",
            "properties": {"description": {"type": "string"}},
            "required": ["description"],
        },
    },
    # v7 skill工具
    {
        "name": "load_skill",
        "description": "Load the full content of a skill by name.",
        "input_schema": {
            "type": "object",
            "properties": {"name": {"type": "string"}},
            "required": ["name"],
        },
    },
    # v8 compact工具
    {
        "name": "compact",
        "description": "Summarize earlier conversation to free context space.",
        "input_schema": {"type": "object", "properties": {"focus": {"type": "string"}}},
    },
    # v12 task工具
    {
        "name": "create_task",
        "description": "Create a new task with optional blockedBy dependencies.",
        "input_schema": {
            "type": "object",
            "properties": {
                "subject": {"type": "string"},
                "description": {"type": "string"},
                "blockedBy": {"type": "array", "items": {"type": "string"}},
            },
            "required": ["subject"],
        },
    },
    {
        "name": "list_tasks",
        "description": "List all tasks with status, owner, and dependencies.",
        "input_schema": {"type": "object", "properties": {}, "required": []},
    },
    {
        "name": "get_task",
        "description": "Get full details of a specific task by ID.",
        "input_schema": {
            "type": "object",
            "properties": {"task_id": {"type": "string"}},
            "required": ["task_id"],
        },
    },
    {
        "name": "claim_task",
        "description": "Claim a pending task. Sets owner, changes status to in_progress.",
        "input_schema": {
            "type": "object",
            "properties": {"task_id": {"type": "string"}},
            "required": ["task_id"],
        },
    },
    {
        "name": "complete_task",
        "description": "Complete an in-progress task. Reports unblocked downstream tasks.",
        "input_schema": {
            "type": "object",
            "properties": {"task_id": {"type": "string"}},
            "required": ["task_id"],
        },
    },
    # v14 cron工具
    {
        "name": "schedule_cron",
        "description": "Schedule a cron job. cron is 5-field: min hour dom month dow.",
        "input_schema": {
            "type": "object",
            "properties": {
                "cron": {"type": "string", "description": "5-field cron expression"},
                "prompt": {
                    "type": "string",
                    "description": "Message to inject when fired",
                },
                "recurring": {
                    "type": "boolean",
                    "description": "True=recurring, False=one-shot",
                },
                "durable": {"type": "boolean", "description": "True=persist to disk"},
            },
            "required": ["cron", "prompt"],
        },
    },
    {
        "name": "list_crons",
        "description": "List all registered cron jobs.",
        "input_schema": {"type": "object", "properties": {}, "required": []},
    },
    {
        "name": "cancel_cron",
        "description": "Cancel a cron job by ID.",
        "input_schema": {
            "type": "object",
            "properties": {"job_id": {"type": "string"}},
            "required": ["job_id"],
        },
    },
    # v15 agent team工具
    {
        "name": "spawn_teammate",
        "description": "Spawn a teammate agent in a background thread.",
        "input_schema": {
            "type": "object",
            "properties": {
                "name": {"type": "string"},
                "role": {"type": "string"},
                "prompt": {"type": "string"},
            },
            "required": ["name", "role", "prompt"],
        },
    },
    {
        "name": "send_message",
        "description": "Send a message to a teammate via MessageBus.",
        "input_schema": {
            "type": "object",
            "properties": {"to": {"type": "string"}, "content": {"type": "string"}},
            "required": ["to", "content"],
        },
    },
    {
        "name": "check_inbox",
        "description": "Check Lead's inbox for teammate messages.",
        "input_schema": {"type": "object", "properties": {}, "required": []},
    },
    # v16 protocol工具
    {
        "name": "request_shutdown",
        "description": "Request a teammate to shut down gracefully.",
        "input_schema": {
            "type": "object",
            "properties": {"teammate": {"type": "string"}},
            "required": ["teammate"],
        },
    },
    {
        "name": "request_plan",
        "description": "Ask a teammate to submit a plan for review.",
        "input_schema": {
            "type": "object",
            "properties": {"teammate": {"type": "string"}, "task": {"type": "string"}},
            "required": ["teammate", "task"],
        },
    },
    {
        "name": "review_plan",
        "description": "Approve or reject a submitted plan by request_id.",
        "input_schema": {
            "type": "object",
            "properties": {
                "request_id": {"type": "string"},
                "approve": {"type": "boolean"},
                "feedback": {"type": "string"},
            },
            "required": ["request_id", "approve"],
        },
    },
    # v18 worktree工具
    {
        "name": "create_worktree",
        "description": "Create an isolated git worktree with its own branch.",
        "input_schema": {
            "type": "object",
            "properties": {"name": {"type": "string"}, "task_id": {"type": "string"}},
            "required": ["name"],
        },
    },
    {
        "name": "remove_worktree",
        "description": "Remove a worktree. Refuses if uncommitted changes unless discard_changes=true.",
        "input_schema": {
            "type": "object",
            "properties": {
                "name": {"type": "string"},
                "discard_changes": {"type": "boolean"},
            },
            "required": ["name"],
        },
    },
    {
        "name": "keep_worktree",
        "description": "Keep a worktree for manual review.",
        "input_schema": {
            "type": "object",
            "properties": {"name": {"type": "string"}},
            "required": ["name"],
        },
    },
]

TOOL_HANDLERS = {
    "bash": run_bash,
    "read_file": run_read,
    "write_file": run_write,
    "edit_file": run_edit,
    "glob": run_glob,
    "todo_write": run_todo_write,
    "task": spawn_subagent,
    "load_skill": load_skill,
    "create_task": run_create_task,
    "list_tasks": run_list_tasks,
    "get_task": run_get_task,
    "claim_task": run_claim_task,
    "complete_task": run_complete_task,
    "schedule_cron": run_schedule_cron,
    "list_crons": run_list_crons,
    "cancel_cron": run_cancel_cron,
    "spawn_teammate": run_spawn_teammate,
    "send_message": run_send_message,
    "check_inbox": run_check_inbox,
    "request_shutdown": run_request_shutdown,
    "request_plan": run_request_plan,
    "review_plan": run_review_plan,
    "create_worktree": run_create_worktree,
    "remove_worktree": run_remove_worktree,
    "keep_worktree": run_keep_worktree,
}

""" v8 子agent工具定义与映射 """

SUB_TOOLS = [
    {
        "name": "bash",
        "description": "Run a shell command.",
        "input_schema": {
            "type": "object",
            "properties": {"command": {"type": "string"}},
            "required": ["command"],
        },
    },
    {
        "name": "read_file",
        "description": "Read file contents.",
        "input_schema": {
            "type": "object",
            "properties": {"path": {"type": "string"}},
            "required": ["path"],
        },
    },
    {
        "name": "write_file",
        "description": "Write content to a file.",
        "input_schema": {
            "type": "object",
            "properties": {"path": {"type": "string"}, "content": {"type": "string"}},
            "required": ["path", "content"],
        },
    },
    {
        "name": "edit_file",
        "description": "Replace exact text in a file once.",
        "input_schema": {
            "type": "object",
            "properties": {
                "path": {"type": "string"},
                "old_text": {"type": "string"},
                "new_text": {"type": "string"},
            },
            "required": ["path", "old_text", "new_text"],
        },
    },
    {
        "name": "glob",
        "description": "Find files matching a glob pattern.",
        "input_schema": {
            "type": "object",
            "properties": {"pattern": {"type": "string"}},
            "required": ["pattern"],
        },
    },
]

SUB_HANDLERS = {
    "bash": run_bash,
    "read_file": run_read,
    "write_file": run_write,
    "edit_file": run_edit,
    "glob": run_glob,
}


""" v13 后台task """

_bg_counter = 0
background_tasks: dict[str, dict] = {}  # bg_id → {tool_use_id, command, status}
background_results: dict[str, str] = {}  # bg_id → output
background_lock = threading.Lock()


# 备用启发式函数：可能需要超过 30 秒的commands。
def is_slow_operation(tool_name: str, tool_input: dict) -> bool:
    if tool_name != "bash":
        return False
    cmd = tool_input.get("command", "").lower()
    slow_keywords = [
        "install",
        "build",
        "test",
        "deploy",
        "compile",
        "docker build",
        "pip install",
        "npm install",
        "cargo build",
        "pytest",
        "make",
    ]
    return any(kw in cmd for kw in slow_keywords)


# 显式模型请求优先，否则回退到启发式函数
def should_run_background(tool_name: str, tool_input: dict) -> bool:
    if tool_input.get("run_in_background"):
        return True
    return is_slow_operation(tool_name, tool_input)


# 在守护线程中运行工具，返回后台任务ID
def start_background_task(block) -> str:
    global _bg_counter
    _bg_counter += 1
    bg_id = f"bg_{_bg_counter:04d}"
    cmd = block.input.get("command", block.name)

    def worker():
        result = execute_tool(block)
        # with用于自动获取和释放锁，防止数据竞争
        with background_lock:
            background_tasks[bg_id]["status"] = "completed"
            background_results[bg_id] = result

    with background_lock:
        background_tasks[bg_id] = {
            "tool_use_id": block.id,
            "command": cmd,
            "status": "running",
        }
    # 创建守护进程，配置线程启动后执行worker函数
    thread = threading.Thread(target=worker, daemon=True)
    thread.start()
    print(f"  \033[33m[background] dispatched {bg_id}: {cmd[:40]}\033[0m")
    return bg_id


# 收集已完成的后台结果作为 task_notification 消息
def collect_background_results() -> list[str]:
    with background_lock:
        ready_ids = [
            bid
            for bid, task in background_tasks.items()
            if task["status"] == "completed"
        ]
    notifications = []
    for bg_id in ready_ids:
        with background_lock:
            task = background_tasks.pop(bg_id)
            output = background_results.pop(bg_id, "")
        summary = output[:200] if len(output) > 200 else output
        notifications.append(
            f"<task_notification>\n"
            f"  <task_id>{bg_id}</task_id>\n"
            f"  <status>completed</status>\n"
            f"  <command>{task['command']}</command>\n"
            f"  <summary>{summary}</summary>\n"
            f"</task_notification>"
        )
        print(
            f"  \033[32m[background done] {bg_id}: "
            f"{task['command'][:40]} ({len(output)} chars)\033[0m"
        )
    return notifications


# 非破坏性：如果任何后台任务为completed并且正在等待被收集，则为真。收件箱轮询器在其唤醒状态下使用此功能
def has_pending_background() -> bool:
    with background_lock:
        return any(t["status"] == "completed" for t in background_tasks.values())


""" v11 错误恢复 """

# 常量
ESCALATED_MAX_TOKENS = 64000
DEFAULT_MAX_TOKENS = 8000
MAX_RECOVERY_RETRIES = 3
MAX_RETRIES = 10
BASE_DELAY_MS = 500
MAX_CONSECUTIVE_529 = 3
CONTINUATION_PROMPT = (
    "Output token limit hit. Resume directly — "
    "no apology, no recap. Pick up mid-thought."
)


# 跟踪整个循环的恢复尝试
class RecoveryState:
    def __init__(self):
        self.has_escalated = False
        self.recovery_count = 0
        self.consecutive_529 = 0
        self.has_attempted_reactive_compact = False
        self.current_model = PRIMARY_MODEL


# 带抖动的指数退避。retry_after（由服务端计算并决定）优先
def retry_delay(attempt, retry_after=None):
    if retry_after:
        return retry_after
    # 指数退避，延迟不断翻倍，上限为32秒
    base = min(BASE_DELAY_MS * (2**attempt), 32000) / 1000
    # 随机抖动
    jitter = random.uniform(0, base * 0.25)
    return base + jitter


# 临时故障的指数退避 (429/529)。非临时故障会重新抛出给外部处理程序。
def with_retry(fn, state: RecoveryState):
    for attempt in range(MAX_RETRIES):
        try:
            # fn为可调用对象（函数/lambda），fn()执行传入的函数
            result = fn()
            state.consecutive_529 = 0
            return result
        except Exception as e:
            # 获取异常类的名称和异常的描述信息
            name = type(e).__name__
            msg = str(e).lower()

            # 429 速率限制 -> 指数退避
            if "ratelimit" in name.lower() or "429" in msg:
                delay = retry_delay(attempt)
                print(
                    f"  \033[33m[429 rate limit] retry {attempt+1}/{MAX_RETRIES},"
                    f" wait {delay:.1f}s\033[0m"
                )
                time.sleep(delay)
                continue

            # 529重载 -> 指数退避 + 回退模型
            if "overloaded" in name.lower() or "529" in msg or "overloaded" in msg:
                state.consecutive_529 += 1
                if state.consecutive_529 >= MAX_CONSECUTIVE_529:
                    if FALLBACK_MODEL:
                        # 切换备用模型
                        state.current_model = FALLBACK_MODEL
                        state.consecutive_529 = 0
                        print(
                            f"  \033[31m[529 x{MAX_CONSECUTIVE_529}]"
                            f" switching to {FALLBACK_MODEL}\033[0m"
                        )
                    else:
                        state.consecutive_529 = 0
                        print(
                            f"  \033[31m[529 x{MAX_CONSECUTIVE_529}]"
                            f" no FALLBACK_MODEL_ID configured, continuing retry\033[0m"
                        )
                delay = retry_delay(attempt)
                print(
                    f"  \033[33m[529 overloaded] retry {attempt+1}/{MAX_RETRIES},"
                    f" wait {delay:.1f}s\033[0m"
                )
                time.sleep(delay)
                continue

            # 非临时故障 -> 重新丢给外部的try/except
            raise
    raise RuntimeError(f"Max retries ({MAX_RETRIES}) exceeded")


# 检查 API 错误是否指示提示词/上下文太长
def is_prompt_too_long_error(e: Exception) -> bool:
    msg = str(e).lower()
    return (
        ("prompt" in msg and "long" in msg)
        or "prompt_is_too_long" in msg
        or "context_length_exceeded" in msg
        or "max_context_window" in msg
    )


def reactive_compact(messages: list) -> list:
    """紧急压缩——教学版本保留最后 N 条消息。

    实际 CC 通过 LLM 生成压缩摘要，然后使用

    压缩后的消息列表重试。教学版本简化为尾部保留，

    因为 v8/v9 已经涵盖了基于 LLM 的压缩。"""
    print("  \033[31m[reactive compact] trimming to last 5 messages\033[0m")
    tail = messages[-5:]
    return [
        {
            "role": "user",
            "content": "[Reactive compact] Earlier conversation trimmed. "
            "Continue from where you left off.",
        },
        # "*"是可迭代对象解包运算符，将可迭代对象的每个元素"展开"到外层容器中
        *tail,
    ]


""" v4 hook """

HOOKS = {"UserPromptSubmit": [], "PreToolUse": [], "PostToolUse": [], "Stop": []}


def register_hook(event: str, callback):
    HOOKS[event].append(callback)


# "*"表示可变位置参数，除了event之外的所有参数都打包成一个元组赋给args，最终传递给回调函数
def trigger_hooks(event: str, *args):
    for callback in HOOKS[event]:
        result = callback(*args)
        # 第一个返回非None的回调“短路”，后续回调不再执行
        if result is not None:
            return result
    return None


""" 
v3 三重权限检验
v4 打包权限检验逻辑为hook
"""

# 硬性拒绝列表
DENY_LIST = ["rm -rf /", "sudo", "shutdown", "reboot", "mkfs", "dd if=", "> /dev/sda"]
DESTRUCTIVE = ["rm ", "> /etc/", "chmod 777"]


# 工具调用之前：v3权限检验移动至此
def permission_hook(block):

    if block.name == "bash":
        for pattern in DENY_LIST:
            if pattern in block.input.get("command", ""):
                print(f"\n\033[31m⛔ Blocked: '{pattern}'\033[0m")
                return "Permission denied by deny list"
        for kw in DESTRUCTIVE:
            if kw in block.input.get("command", ""):
                print(f"\n\033[33m⚠  Potentially destructive command\033[0m")
                print(f"   Tool: {block.name}({block.input})")
                choice = input("   Allow? [y/N] ").strip().lower()
                if choice not in ("y", "yes"):
                    return "Permission denied by user"
    if block.name in ("write_file", "edit_file"):
        path = block.input.get("path", "")
        if not (WORKDIR / path).resolve().is_relative_to(WORKDIR):
            print(f"\n\033[33m⚠  Writing outside workspace\033[0m")
            print(f"   Tool: {block.name}({block.input})")
            choice = input("   Allow? [y/N] ").strip().lower()
            if choice not in ("y", "yes"):
                return "Permission denied by user"
    return None


# 工具调用之前：记录每个工具调用的日志
def log_hook(block):
    # 前两个参数的预览（最多60个字符）
    args_preview = str(list(block.input.values())[:2])[:60]
    print(f"\033[90m[HOOK] {block.name}({args_preview})\033[0m")
    return None


# 工具调用之后：警告大输出
def large_output_hook(block, output):
    if len(str(output)) > 100000:
        print(
            f"\033[33m[HOOK] ⚠ Large output from {block.name}: {len(str(output))} chars\033[0m"
        )
    return None


# 用户提问提交时：在用户提问抵达模型之前触发，打印工作目录
def context_inject_hook(query: str):
    print(f"\033[90m[HOOK] UserPromptSubmit: working in {WORKDIR}\033[0m")
    return None


# 停止循环时：统计工具调用次数
def summary_hook(messages: list):
    tool_count = sum(
        1
        for m in messages
        # 对那些类型为list的content进行遍历，统计其中类型为tool_result的字典数量
        for b in (m.get("content") if isinstance(m.get("content"), list) else [])
        if isinstance(b, dict) and b.get("type") == "tool_result"
    )
    print(f"\033[90m[HOOK] Stop: session used {tool_count} tool calls\033[0m")
    return None


# 注册hook
register_hook("PreToolUse", permission_hook)
register_hook("PreToolUse", log_hook)
register_hook("PostToolUse", large_output_hook)
register_hook("UserPromptSubmit", context_inject_hook)
register_hook("Stop", summary_hook)



# 从真实状态导出上下文
def update_context() -> dict:
    memories = ""
    if MEMORY_INDEX.exists():
        content = MEMORY_INDEX.read_text().strip()
        if content:
            memories = content
    return {
        "enabled_tools": list(TOOL_HANDLERS.keys()),
        "workspace": str(WORKDIR),
        "memories": memories,
        "skills_catalog": list_skills(),
    }


""" 
v2 修改工具执行部分
v3 插入检查权限函数
v4 插入hook触发
v5 提醒计数器
v8 历史压缩管道
v9 注入与提取记忆
v10 组装与更新提示词
v11 错误恢复
v14 cron
"""

rounds_since_todo = 0

MAX_REACTIVE_RETRIES = 1


def agent_loop(messages: list, context: dict):
    global rounds_since_todo
    reactive_retries = 0

    # v9 注入相关记忆到当前用户轮次中（消息记录的最后一条）
    memories_content = load_memories(messages)
    memory_turn = (
        len(messages) - 1
        if messages and isinstance(messages[-1].get("content"), str)
        else None
    )
    # v19 动态组装工具
    tools, handlers = assemble_tool_pool()
    system = assemble_system_prompt(context)
    # v11 定义错误恢复变量
    state = RecoveryState()
    max_tokens = DEFAULT_MAX_TOKENS

    while True:
        # v9 保存压缩前的快照以便提取精确的记忆
        pre_compress = [
            (
                m
                if isinstance(m, dict)
                else {"role": m.get("role", ""), "content": str(m.get("content", ""))}
            )
            for m in messages
        ]

        # v8 执行顺序：L3 budget → L1 snip → L2 micro
        # L3（budget）必须在 L2（micro）前面，先对内容落盘，再替换旧工具结果为占位符
        # "[:]"为原地替换，直接修改原列表的内容
        messages[:] = tool_result_budget(messages)
        messages[:] = snip_compact(messages)
        messages[:] = micro_compact(messages)

        # v8 当tokens仍然超过阈值时调用llm进行总结——框架兜底
        if estimate_size(messages) > CONTEXT_LIMIT:
            print("[auto compact]")
            messages[:] = compact_history(messages)

        # 每轮循环前检查是否需要提醒更新todo列表
        # 先压缩，后提醒
        if rounds_since_todo >= 3 and messages:
            messages.append(
                {"role": "user", "content": "<reminder>Update your todos.</reminder>"}
            )
            rounds_since_todo = 0

        # L4 消费已触发的 cron 作业 → 作为消息注入
        fired = consume_cron_queue()
        for job in fired:
            messages.append({"role": "user", "content": f"[Scheduled] {job.prompt}"})
            print(f"  \033[35m[inject cron] {job.prompt[:50]}\033[0m")

        # 注入记忆
        try:
            request_messages = messages
            if (
                memories_content
                and memory_turn is not None
                and memory_turn < len(messages)  # 防止压缩后索引越界
            ):
                # 需要注入记忆，创建原对象的浅拷贝，避免修改
                request_messages = messages.copy()
                # 第一个参数展开全部键值对，第二个参数仅修改content字段
                request_messages[memory_turn] = {
                    **messages[memory_turn],
                    "content": memories_content
                    + "\n\n"
                    + messages[memory_turn]["content"],
                }

            # v11 调用llm：with_retry处理429/529，外层处理rest
            try:
                # 首次循环仅有用户提问，之后循环包含助手回复与工具结果
                response = with_retry(
                    lambda mt=max_tokens, mdl=state.current_model: client.messages.create(
                        model=mdl,
                        system=system,
                        messages=messages,
                        tools=tools,
                        max_tokens=mt,
                    ),
                    state,
                )
            except Exception as e:
                # 提示词太长 -> 单次调用reactive compact
                if is_prompt_too_long_error(e):
                    if not state.has_attempted_reactive_compact:
                        messages[:] = reactive_compact(messages)
                        state.has_attempted_reactive_compact = True
                        continue
                    print(
                        "  \033[31m[unrecoverable] still too long after compact\033[0m"
                    )
                    messages.append(
                        {
                            "role": "assistant",
                            "content": [
                                {
                                    "type": "text",
                                    "text": "[Error] Context too large, cannot continue.",
                                }
                            ],
                        }
                    )
                    return

                # 无法恢复
                name = type(e).__name__
                print(f"  \033[31m[unrecoverable] {name}: {str(e)[:100]}\033[0m")
                messages.append(
                    {
                        "role": "assistant",
                        "content": [
                            {"type": "text", "text": f"[Error] {name}: {str(e)[:200]}"}
                        ],
                    }
                )
                return

            # 到达max_tokens -> escalate或continue
            if response.stop_reason == "max_tokens":
                # 首次升级：不要附加截断的输出到messages，升级max_tokens并重试相同的请求
                if not state.has_escalated:
                    max_tokens = ESCALATED_MAX_TOKENS
                    state.has_escalated = True
                    print(
                        f"  \033[33m[max_tokens] escalating"
                        f" {DEFAULT_MAX_TOKENS} -> {ESCALATED_MAX_TOKENS}\033[0m"
                    )
                    continue
                # 64K仍被截断：保存截断输出+continuation prompt
                messages.append({"role": "assistant", "content": response.content})
                if state.recovery_count < MAX_RECOVERY_RETRIES:
                    messages.append({"role": "user", "content": CONTINUATION_PROMPT})
                    state.recovery_count += 1
                    print(
                        f"  \033[33m[max_tokens] continuation"
                        f" {state.recovery_count}/{MAX_RECOVERY_RETRIES}\033[0m"
                    )
                    continue
                print("  \033[31m[max_tokens] recovery limit reached\033[0m")
                return

            reactive_retries = 0
        # 当api返回 context 超长错误且还没达到最大重试次数时，进行reactive compact
        except Exception as e:
            if (
                "prompt_too_long" in str(e).lower()
                or "too many tokens" in str(e).lower()
            ) and reactive_retries < MAX_REACTIVE_RETRIES:
                print("[reactive compact]")
                messages[:] = reactive_compact(messages)
                reactive_retries += 1
                continue
            # raise 单独使用（不带异常对象）会重新抛出当前捕获的异常,让上层（__main__）处理，程序最终会崩溃退出而不是卡在死循环里
            raise

        # 将助手的回复添加到消息列表中
        messages.append({"role": "assistant", "content": response.content})

        if response.stop_reason != "tool_use":
            # v9 从压缩前快照中提取完整记忆
            extract_memories(pre_compress)
            consolidate_memories()

            force = trigger_hooks("Stop", messages)
            # 如果hook返回非None，则将其作为强制用户输入，继续循环，防止ai偷懒
            # 在v4版本中唯一作用就是打印工具调用次数
            if force:
                messages.append({"role": "user", "content": force})
                continue
            return

        """ 
        response.content包含回复的内容块，如：
        response.content == [
            ContentBlock(
                type="text",
                text="好的，我来用 bash 统计各代码文件的行数。"
            ),
            ContentBlock(
                type="tool_use",
                id="toolu_01ABC123DEF",
                name="bash",
                input={
                    "command": "find . -type f \\( -name '*.py' -o -name '*.ts' -o -name '*.java' \\) -exec wc -l {} + | sort -n"
                }
            )
        ]
        大模型生成原始输出，由Anthropic API 的服务端进行格式处理
        """

        rounds_since_todo += 1
        # 执行每个工具调用，收集结果
        results = []
        for block in response.content:
            if block.type != "tool_use":
                continue
            print(f"\033[36m> {block.name}\033[0m")

            # v8 模型认为上下文太长时主动压缩——模型自主控制
            if block.name == "compact":
                messages[:] = compact_history(messages)
                results.append(
                    {
                        "type": "tool_result",
                        "tool_use_id": block.id,
                        "content": "[Compacted. Conversation history has been summarized.]",
                    }
                )
                messages.append({"role": "user", "content": results})
                # 结束当前轮次，从压缩上下文中重新开始
                break

            # v4 hook代替v3权限检验，PreToolUse出现问题就打印反馈给模型，继续下一次循环
            # 与v5 todo同样，先压缩再检验
            blocked = trigger_hooks("PreToolUse", block)
            if blocked:
                results.append(
                    {
                        "type": "tool_result",
                        "tool_use_id": block.id,
                        "content": str(blocked),
                    }
                )
                continue

            if should_run_background(block.name, block.input):
                bg_id = start_background_task(block)
                results.append(
                    {
                        "type": "tool_result",
                        "tool_use_id": block.id,
                        "content": f"[Background task {bg_id} started] "
                        f"Command: {block.input.get('command', '')}. "
                        f"Result will be available when complete.",
                    }
                )
            else:
                output = execute_tool(block)
                trigger_hooks("PostToolUse", block, output)
                if block.name == "todo_write":
                    rounds_since_todo = 0
                print(str(output)[:200])
                results.append(
                    {"type": "tool_result", "tool_use_id": block.id, "content": output}
                )
        # for-else结构，只有在 for 循环正常结束（没有 break）时才执行
        else:
            # 在一条用户消息中注入工具结果 + 后台通知
            user_content = list(results)
            bg_notifications = collect_background_results()
            if bg_notifications:
                for notif in bg_notifications:
                    user_content.append({"type": "text", "text": notif})
                print(
                    f"  \033[32m[inject] {len(bg_notifications)} background "
                    f"notification(s)\033[0m"
                )

            # 压缩未被调用
            # 将工具结果反馈回消息列表，循环继续
            handler = handlers.get(block.name)
            output = handler(**block.input) if handler else "Unknown"
            print(str(output)[:300])
            results.append({"type": "tool_result",
                            "tool_use_id": block.id, "content": output})
            messages.append({"role": "user", "content": results})

            # 每轮工具后重新评估上下文和提示
            if any(b.name == "connect_mcp" for b in response.content
               if b.type == "tool_use"):
                tools, handlers = assemble_tool_pool()
                context = update_context()
                system = assemble_system_prompt(context)
            continue
        # 压缩被调用，results已附加到上方，继续下一轮while，不能再继续处理其他 block
        continue


session_history: list = []
session_context = update_context()
had_teammates = False


# 打印最新助手消息中的文本块
def print_latest_assistant_text(messages: list):
    if not messages:
        return
    msg = messages[-1]
    if not isinstance(msg, dict) or msg.get("role") != "assistant":
        return
    content = msg.get("content", "")
    if isinstance(content, str):
        print(content)
        return
    for block in content:
        if getattr(block, "type", None) == "text":
            print(block.text)
        elif isinstance(block, dict) and block.get("type") == "text":
            print(block.get("text", ""))


# 运行一个agent轮次
def run_agent_turn(user_query: str | None = None):
    global session_context, had_teammates
    if user_query is not None:
        session_history.append({"role": "user", "content": user_query})
    agent_loop(session_history, session_context)
    session_context = update_context()
    print_latest_assistant_text(session_history)

    # 当所有队友线程都完成后通知一次
    if active_teammates:
        had_teammates = True
    elif had_teammates and not has_pending_background():
        print("\033[32m[all teammates done]\033[0m")
        had_teammates = False
    print()


if __name__ == "__main__":
    print("Version 19: MCP")
    print("输入问题，回车发送。输入 q 退出。\n")

    while True:
        try:
            query = input("\033[36ms19 >> \033[0m")
        except (EOFError, KeyboardInterrupt):
            break
        if query.strip().lower() in ("q", "exit", ""):
            break

        run_agent_turn(query)

        # 同步检查收件箱 → 路由协议 + 注入历史
        inbox_msgs = consume_lead_inbox(route_protocol=True)
        if inbox_msgs:
            inbox_text = "\n".join(
                f"From {m['from']}: {m['content'][:200]}" for m in inbox_msgs
            )
            session_history.append(
                {"role": "user", "content": f"[Inbox]\n{inbox_text}"}
            )
            print(f"\n\033[33m[Inbox: {len(inbox_msgs)} messages injected]\033[0m")
            # 让 agent 处理队友消息
            run_agent_turn()
        print()
