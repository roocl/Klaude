# 兼容契约数据

本目录保存 Klaude 对外协议与持久化格式的冻结行为，用于阻止实现变化意外破坏公共契约。

Java 测试按 JSON 语义比较这些数据：JSON 对象的字段顺序、缩进和换行格式不属于契约；字段含义、类型、缺失值与显式 `null` 的区别才属于契约。

## 目录内容

- `schema/commands.schema.json`：12 种 command 的判别联合 Schema。
- `schema/events.schema.json`：24 种 event 的判别联合 Schema。
- `fixtures/commands.jsonl`、`fixtures/events.jsonl`：有效 command 和 event 的代表性样本。
- `fixtures/ipc-*.jsonl`：JSON-RPC 成功响应和错误响应行为。
- `fixtures/subscription-matching.json`：topic 通配符与 run scope 匹配行为。
- `fixtures/session/`：session 元数据、对话线程、notes、损坏行、孤立工具调用和 compact 场景。
- `fixtures/configuration.json`：默认配置、配置优先级和错误结果。
- `fixtures/agent-loop.json`：Agent Loop 的停止、错误和取消结果。
- `fixtures/tasks/`、`fixtures/policy/`、`fixtures/trace/`：任务、权限策略和运行追踪的文件样本。

## 使用方式

Gradle 测试会直接读取这些 fixtures。协议 round-trip 可通过以下命令验证：

```powershell
.\gradlew.bat --no-daemon roundTripFixtures
```

```sh
./gradlew --no-daemon roundTripFixtures
```

生成结果位于 `build/contract-roundtrip/`。

## 更新规则

这些文件是冻结的行为基线，不应为了让测试通过而直接手工修改。

如确实需要更新，必须把它作为一次明确的公共行为变更：

1. 先添加或更新测试，描述预期的公共行为。
2. 更新对应 fixture，并审查字段、类型、缺失值和 `null` 语义。
3. 运行 Java 单元测试、集成测试和协议 round-trip。
4. 在变更记录中明确说明兼容性影响。

## 历史标识符

fixtures 可能包含为了读取既有用户数据而保留的历史标识符。这项例外只适用于冻结数据，不允许在新的 Java package、类型、配置键或产品文档中重新使用这些标识符。
