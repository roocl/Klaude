# Klaude Java

Klaude Java 是一个基于 Java 21 的本地 AI Agent Runtime。它以 daemon 形式运行，通过 TCP 提供 NDJSON JSON-RPC 2.0 接口，负责模型调用、Agent Loop、工具执行、权限控制、会话持久化、MCP、skills、子 Agent 和运行追踪。

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

### Linux / macOS

```sh
./gradlew --no-daemon clean test integrationTest installDist distZip
./scripts/start-core.sh
```

daemon 默认监听：

```text
127.0.0.1:7437
```

启动脚本会在安装目录不存在时自动执行 `installDist`。Java 是默认且正式的 runtime。

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

测试和离线 demo 不需要 API key。

## 发行包

执行 `distZip` 后会生成可迁移的发行 zip，其中同时包含 Windows 和 Unix 启动器、应用 jar、运行依赖以及内置 skills/profiles。

解压后，在安装 Java 21 的机器上运行：

```text
bin/klaude-core-java.bat    # Windows
bin/klaude-core-java        # Linux / macOS
```

## 安全说明

- 不要提交 `.env`、API key 或用户目录下的 `.klaude` 数据。
- 测试默认使用 fake/scripted provider，不应访问真实模型 endpoint。
- 文件和 Shell 工具受到 workspace 路径、权限策略、超时和输出大小限制。
- `contract/` 是兼容性基线，不应为了让测试通过而随意修改。
