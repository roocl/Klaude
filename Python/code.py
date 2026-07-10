import os
import subprocess
from pathlib import Path

from anthropic import Anthropic
from dotenv import load_dotenv

# 从 .env 文件加载环境变量到 os.environ，true代表覆盖
load_dotenv(override=True)
# 兼容第三方端点
if os.getenv("ANTHROPIC_BASE_URL"):
    # 移除官方鉴权
    os.environ.pop("ANTHROPIC_AUTH_TOKEN", None)

WORKDIR = Path.cwd()
client = Anthropic(base_url=os.getenv("ANTHROPIC_BASE_URL"))
MODEL = os.environ["MODEL_ID"]

SYSTEM = (
    f"You are a coding agent at {WORKDIR}. Use bash to solve tasks. Act, don't explain."
)

# 单bash
TOOLS = [
    {
        "name": "bash",
        "description": "Run a shell command.",
        "input_schema": {
            "type": "object",
            "properties": {"command": {"type": "string"}},
            "required": ["command"],
        },
    }
]


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


""" 
v2新增4个工具 读写改配
"""


def safe_path(p: str) -> Path:
    # 拼接与解析路径
    path = (WORKDIR / p).resolve()
    if not path.is_relative_to(WORKDIR):
        raise ValueError(f"Path escapes workspace: {p}")
    return path


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


""" 
v2 工具定义与分发映射
"""

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
]

TOOL_HANDLERS = {
    "bash": run_bash,
    "read_file": run_read,
    "write_file": run_write,
    "edit_file": run_edit,
    "glob": run_glob,
}

""" 
v3 三重权限检验
"""

# 1. 硬性拒绝列表
DENY_LIST = ["rm -rf /", "sudo", "shutdown", "reboot", "mkfs", "dd if=", "> /dev/sda"]


def check_deny_list(command: str) -> str | None:
    for pattern in DENY_LIST:
        if pattern in command:
            return f"Blocked: '{pattern}' is on the deny list"
    return None


# 2. 规则匹配检查
PERMISSION_RULES = [
    {
        "tools": ["write_file", "edit_file"],
        # lambda表达式，接收参数args，即block.input字典
        "check": lambda args: not (WORKDIR / args.get("path", ""))
        .resolve()
        .is_relative_to(WORKDIR),
        "message": "Writing outside workspace",
    },
    {
        "tools": ["bash"],
        "check": lambda args: any(
            kw in args.get("command", "") for kw in ["rm ", "> /etc/", "chmod 777"]
        ),
        "message": "Potentially destructive command",
    },
]

def check_rules(tool_name: str, args: dict) -> str | None:
    for rule in PERMISSION_RULES:
        if tool_name in rule["tools"] and rule["check"](args):
            return rule["message"]
    return None


# 3. 用户确认
def ask_user(tool_name: str, args: dict, reason: str) -> str:
    print(f"\n\033[33m⚠  {reason}\033[0m")
    print(f"   Tool: {tool_name}({args})")
    choice = input("   Allow? [y/N] ").strip().lower()
    return "allow" if choice in ("y", "yes") else "deny"

# 权限检验链
def check_permission(block) -> bool:
    if block.name == "bash":
        reason = check_deny_list(block.input.get("command", ""))
        if reason:
            print(f"\n\033[31m⛔ {reason}\033[0m")
            return False

    reason = check_rules(block.name, block.input)
    if reason:
        decision = ask_user(block.name, block.input, reason)
        if decision == "deny":
            return False
    return True

""" 
v2 修改工具执行部分
v3 插入检查权限函数
"""


def agent_loop(messages: list):
    while True:
        # 首次循环仅有用户提问，之后循环包含助手回复与工具结果
        response = client.messages.create(
            model=MODEL,
            system=SYSTEM,
            messages=messages,
            tools=TOOLS,
            max_tokens=8000,
        )

        # 将助手的回复添加到消息列表中
        messages.append({"role": "assistant", "content": response.content})

        # 如果模型没有请求使用工具，则结束循环
        if response.stop_reason != "tool_use":
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

        # 执行每个工具调用，收集结果
        results = []
        for block in response.content:
            if block.type != "tool_use":
                continue
            
            print(f"\033[33m> {block.name}\033[0m")
            
            # v3 检查权限
            if not check_permission(block):
                results.append(
                    {
                        "type": "tool_result",
                        "tool_use_id": block.id,
                        "content": f"Permission denied for {block.name}",
                    }
                )
                continue
            
            handler = TOOL_HANDLERS.get(block.name)
            # "**"为解包字典，将字典的键值对作为关键字参数传递给函数
            output = handler(**block.input) if handler else f"Unknown: {block.name}"
            print(output[:200])
            results.append(
                {
                    "type": "tool_result",
                    "tool_use_id": block.id,
                    "content": output,
                }
            )

        # 将工具结果反馈回消息列表，循环继续
        messages.append({"role": "user", "content": results})


if __name__ == "__main__":
    print("Version 3: Permission")
    print("输入问题，回车发送。输入 q 退出。\n")

    history = []
    while True:
        try:
            query = input("\033[36ms03 >> \033[0m")
        except (EOFError, KeyboardInterrupt):
            break
        if query.strip().lower() in ("q", "exit", ""):
            break
        history.append({"role": "user", "content": query})
        agent_loop(history)
        # 遍历最终文本回复的内容块，打印文本部分（防御性检查）
        for block in history[-1]["content"]:
            if getattr(block, "type", None) == "text":
                print(block.text)
        print()
