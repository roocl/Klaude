import ast, json, os, subprocess
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
SKILLS_DIR = WORKDIR / "skills"
client = Anthropic(base_url=os.getenv("ANTHROPIC_BASE_URL"))
MODEL = os.environ["MODEL_ID"]
CURRENT_TODOS: list[dict] = []


# v7 扫描skill目录，解析每个SKILL.md的YAML frontmatter，生成技能列表
def _parse_frontmatter(text: str) -> tuple[dict, str]:
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


# v6 启动时构建技能注册表
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
            meta, body = _parse_frontmatter(raw)
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


# v7 启动时构建SYSTEM提示词，并注入技能目录
def build_system() -> str:
    catalog = list_skills()
    return (
        f"You are a coding agent at {WORKDIR}. "
        f"Skills available:\n{catalog}\n"
        "Use load_skill to get full details when needed."
    )


SYSTEM = build_system()

# v6 子系统提示词
SUB_SYSTEM = (
    f"You are a coding agent at {WORKDIR}. "
    "Complete the task you were given, then return a concise summary. "
    "Do not delegate further."
)

""" 
v2-v6 工具实现
"""


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
            model=MODEL,
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


""" 
v2-v6 工具定义与分发映射
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
}

""" v6 子agent工具定义与映射 """

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


""" 
v2 修改工具执行部分
v3 插入检查权限函数
v4 插入hook触发
v5 提醒计数器
"""

rounds_since_todo = 0


def agent_loop(messages: list):
    global rounds_since_todo
    while True:
        # 每轮循环前检查是否需要提醒更新todo列表
        if rounds_since_todo >= 3 and messages:
            messages.append(
                {"role": "user", "content": "<reminder>Update your todos.</reminder>"}
            )
            rounds_since_todo = 0

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

        if response.stop_reason != "tool_use":
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

            # v4 hook代替v3权限检验，PreToolUse出现问题就打印反馈给模型，继续下一次循环
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

            handler = TOOL_HANDLERS.get(block.name)
            # "**"为解包字典，将字典的键值对作为关键字参数传递给函数
            output = handler(**block.input) if handler else f"Unknown: {block.name}"

            # PostToolUse
            trigger_hooks("PostToolUse", block, output)

            # 重置计数器
            if block.name == "todo_write":
                rounds_since_todo = 0

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
    print("Version 7: Skills")
    print("输入问题，回车发送。输入 q 退出。\n")

    history = []
    while True:
        try:
            query = input("\033[36ms07 >> \033[0m")
        except (EOFError, KeyboardInterrupt):
            break
        if query.strip().lower() in ("q", "exit", ""):
            break
        trigger_hooks("UserPromptSubmit", query)
        history.append({"role": "user", "content": query})
        agent_loop(history)
        # 遍历最终文本回复的内容块，打印文本部分（防御性检查）
        for block in history[-1]["content"]:
            if getattr(block, "type", None) == "text":
                print(block.text)
        print()
