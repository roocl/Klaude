# Klaude Java

Klaude Java 是一个基于 Java 21 的本地 AI Agent Runtime。它以 daemon 形式运行，并提供 CLI 客户端，通过 TCP NDJSON JSON-RPC 2.0 负责模型调用、Agent Loop、工具执行、权限控制、会话持久化、MCP、skills、子 Agent 和运行追踪。

项目不是 Web 服务，也不依赖 Spring Boot。客户端可以是终端程序、桌面应用或任何兼容现有 JSON-RPC wire contract 的外部程序。

## 核心能力

- TCP NDJSON JSON-RPC 通信、事件订阅和 replay
- 流式 LLM provider 接口及 Anthropic adapter
- 可取消、可限制步骤数的 Agent Loop
- JSON Schema 工具参数校验和权限审批
- 文件、目录、Shell、task、notes 等内置工具
- UTF-8 session、thread、task、policy、event 和 trace 持久化
- project/user/built-in skills 与 Agent profiles
- stdio/TCP MCP server 接入
- 前台与后台子 Agent
- 不需要 API key 或外部网络的确定性离线 demo

## 环境要求

- JDK 21
- Windows、Linux 或 macOS

项目已经包含 Gradle Wrapper，不需要单独安装 Gradle。

检查 Java 版本：

```text
java -version
```

Windows 启动脚本会优先使用 `KLAUDE_JAVA_HOME`，随后检查 `JAVA_HOME`、其同级 JDK 和 Gradle 已下载的 toolchain，并选择 Java 21 或更高版本。需要显式指定时：

```powershell
$env:KLAUDE_JAVA_HOME = "E:\Java\jdk-21"
.\scripts\start-core.ps1
```

发行包内的 daemon、CLI 和 TUI 启动器都会优先使用 `KLAUDE_JAVA_HOME`。如果系统级 `JAVA_HOME` 仍需保留 Java 17，可在每个新终端中设置：

```powershell
$env:KLAUDE_JAVA_HOME = "E:\Java\jdk-21"   # PowerShell
```

```bat
set "KLAUDE_JAVA_HOME=E:\Java\jdk-21"      rem CMD
```

## 快速开始

### Windows

运行完整测试并生成发行包：

```powershell
.\gradlew.bat --no-daemon clean test integrationTest installDist distZip
```

启动 daemon：

```powershell
.\scripts\start-core.ps1
```

另开一个终端，通过安装目录中的 CLI 连接 daemon：

```powershell
.\build\install\klaude-core-java\bin\klaude.bat ping
.\build\install\klaude-core-java\bin\klaude.bat run --goal "分析当前项目"
.\build\install\klaude-core-java\bin\klaude.bat chat
.\build\install\klaude-core-java\bin\klaude.bat cancel <run-id>
.\build\install\klaude-core-java\bin\klaude.bat trace
.\build\install\klaude-core-java\bin\klaude-tui.bat
```

### Linux / macOS

```sh
./gradlew --no-daemon clean test integrationTest installDist distZip
./scripts/start-core.sh
```

另开一个终端：

```sh
./build/install/klaude-core-java/bin/klaude ping
./build/install/klaude-core-java/bin/klaude run --goal "分析当前项目"
./build/install/klaude-core-java/bin/klaude chat
./build/install/klaude-core-java/bin/klaude cancel <run-id>
./build/install/klaude-core-java/bin/klaude trace
./build/install/klaude-core-java/bin/klaude-tui
```

daemon 默认监听：

```text
127.0.0.1:7437
```

启动脚本会在安装目录不存在时自动执行 `installDist`。Java 是默认且正式的 runtime。

`chat` 启动时会自动创建会话。普通文本进入多轮 Agent 对话；`/init ...`、`/review ...`、`/orchestrate ...` 等输入会由 daemon 按现有 skill 规则解析；输入 `/exit` 退出客户端。需要执行有副作用的工具时，CLI 会提示本次允许、始终允许或拒绝。

chat 内置命令：

```text
/skills                 列出当前可用 skills
/sessions               按最近更新时间列出 sessions
/resume <session-id>    切换到已有 session
/history                显示当前 session 历史
/compact [关注点]       持久化压缩当前 session
/exit                   退出 CLI
```

CLI 只拦截以上已知命令，其他 slash 输入会原样发送给 daemon，因此项目或用户自定义 skill 不需要修改 CLI。

`run`、`chat` 和 `cancel` 会先探活 daemon。`run` 期间按 Ctrl+C 会尽力向 daemon 发送远程取消；也可以在另一个终端使用 `klaude cancel <run-id>`。`trace` 读取配置中的 `trace.file`，跳过损坏的 JSONL 尾行并输出紧凑时间线。

CLI 退出码：`0` 表示成功，`1` 表示 RPC 或任务失败，`2` 表示参数或配置错误，`3` 表示 daemon 无法连接或连接中断。

`klaude-tui` 提供全屏终端对话界面，显示流式回答、工具与子 Agent 状态、context 占比和权限请求。它支持与 `chat` 相同的本地命令；使用 `klaude-tui --session <session-id>` 可直接恢复已有会话。在不支持 ANSI 的重定向环境中会自动退化为逐屏文本输出。

TUI 会按照 `COLUMNS`、`LINES` 或安全默认值裁剪显示区域。Agent 正在处理消息时会拒绝重复发送，但仍允许本地命令和权限决定；连接中断后状态栏会明确显示 `disconnected`，不会把后续输入伪装成已发送。

阶段六 TUI 命令：`/sessions` 和 `/skills` 会生成编号列表，随后可用 `/resume <编号>` 或 `/skill <编号> [参数]` 选择；`/new [标题]` 创建新会话，`/tools` 展开或折叠工具参数与输出，`/again` 重发上一条消息。输入行末添加 `\` 可继续输入下一行。状态栏显示当前 run、模型、token、context、工具详情模式和子 Agent 数量，并对标题、列表、引用、粗体与行内代码进行基础 Markdown 渲染。

TUI 连接意外中断后会以 250ms 到 5s 的有界退避自动重连。重连成功后使用当前 Run ID 调用既有 `replay_from_run` 协议能力，回放断线期间的事件并重新注册实时订阅；重复事件会在界面模型中去重，已经由 granted/denied 解决的权限请求不会再次提示。Session ID 保留在客户端中，因此恢复连接后可继续当前会话。

## 离线演示

离线 demo 使用确定性的 fake LLM provider、临时状态目录和本地随机端口，不读取模型 API key，也不访问外部网络。

Windows：

```powershell
.\scripts\demo.ps1
```

Linux / macOS：

```sh
./scripts/demo.sh
```

demo 会验证 daemon 启动、一次完整 Agent Run、多个并发 session，以及 malformed JSON 后的错误恢复。

## 项目结构

```text
.
├── contract/                 冻结的协议与持久化兼容 fixtures
├── gradle/                   Gradle Wrapper 和依赖版本定义
├── scripts/                  启动和离线 demo 脚本
├── src/
│   ├── main/
│   │   ├── java/             生产代码
│   │   └── resources/        内置 skills 和 Agent profiles
│   ├── test/                 单元和模块测试
│   └── integrationTest/      进程、Socket 和发行包测试
├── build.gradle.kts          单项目构建配置
├── settings.gradle.kts       Gradle 项目设置
├── gradle.properties         Gradle 运行参数
├── gradlew                   Unix Gradle Wrapper
└── gradlew.bat               Windows Gradle Wrapper
```

生产代码统一位于 `src/main/java/io/klaude`，主要 package 包括：

- `protocol`：JSON-RPC command、result、event 和序列化规则
- `transport`：Netty TCP、NDJSON framing、RPC dispatch 和事件订阅
- `agent`：Agent Loop、run 生命周期和执行上下文
- `llm`：模型 provider 接口、fake provider 和 Anthropic adapter
- `tool`：工具注册、校验、权限和内置工具
- `session`：多轮会话、上下文、compact 和 task 持久化
- `extension`：skills、Agent profiles 和子 Agent
- `mcp`：MCP client、transport 和工具 adapter
- `observability`：event、trace 和 run 文件布局
- `daemon`：配置加载、依赖装配和进程生命周期
- `cli`：daemon RPC 客户端、流式事件输出和终端交互

## 常用 Gradle 任务

```powershell
# 单元测试
.\gradlew.bat --no-daemon test

# 进程、Socket 和发行包集成测试
.\gradlew.bat --no-daemon integrationTest

# 运行全部验证
.\gradlew.bat --no-daemon check

# 创建可直接运行的安装目录
.\gradlew.bat --no-daemon installDist

# 创建发行 zip
.\gradlew.bat --no-daemon distZip

# 运行离线 demo
.\gradlew.bat --no-daemon offlineDemo

# 对冻结协议 fixtures 做 Java round-trip
.\gradlew.bat --no-daemon roundTripFixtures
```

构建产物位于：

- 安装目录：`build/install/klaude-core-java/`
- 发行包：`build/distributions/`
- 应用 jar：`build/libs/`
- 测试报告：`build/reports/tests/`

## 配置

配置优先级从低到高为：

```text
默认值 → 用户 config.toml → 项目 config.toml → .env → 进程环境变量
```

主要位置：

- 用户配置：`~/.klaude/config.toml`
- 项目配置：`<workspace>/.klaude/config.toml`
- 显式配置文件：环境变量 `KLAUDE_CONFIG`
- 用户 session：`~/.klaude/sessions`
- 用户 skills/profiles：`~/.klaude/skills`、`~/.klaude/agents`
- 项目 skills/profiles：`.klaude/skills`、`.klaude/agents`

可以通过 `KLAUDE_HOST` 和 `KLAUDE_PORT` 修改监听地址。端口设为 `0` 时由操作系统分配临时端口。

只有真实 Anthropic 模型调用需要设置：

```text
ANTHROPIC_API_KEY
```

可直接在项目根目录 `.env` 中配置，daemon 启动时会自动加载：

```dotenv
KLAUDE_JAVA_HOME=E:/Java/jdk-21
ANTHROPIC_API_KEY=
KLAUDE_HOST=127.0.0.1
KLAUDE_PORT=7437
```

留空的 API Key 会被视为未配置。进程环境变量优先于 `.env`，便于 CI 或临时终端覆盖；`.env` 已被 Git 忽略，不会进入版本库。

发行包启动器会在 JVM 启动前读取当前工作目录或发行根目录的 `.env`，因此
`KLAUDE_JAVA_HOME` 也可以放在项目 `.env` 中。其优先级为：进程环境变量、当前目录
`.env`、发行根目录 `.env`、`JAVA_HOME`、PATH。

测试和离线 demo 不需要 API key。

## 发行包

执行 `distZip` 后会生成可迁移的发行 zip，其中同时包含 Windows 和 Unix 启动器、应用 jar、运行依赖以及内置 skills/profiles。

解压后，在安装 Java 21 的机器上运行：

```text
bin/klaude-core-java.bat    # Windows
bin/klaude-core-java        # Linux / macOS
bin/klaude.bat              # Windows CLI
bin/klaude                  # Linux / macOS CLI
bin/klaude-tui.bat          # Windows TUI
bin/klaude-tui              # Linux / macOS TUI
```

## 安全说明

- 不要提交 `.env`、API key 或用户目录下的 `.klaude` 数据。
- 测试默认使用 fake/scripted provider，不应访问真实模型 endpoint。
- 文件和 Shell 工具受到 workspace 路径、权限策略、超时和输出大小限制。
- `contract/` 是兼容性基线，不应为了让测试通过而随意修改。
