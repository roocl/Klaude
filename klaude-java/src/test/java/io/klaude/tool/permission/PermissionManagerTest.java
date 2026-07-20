package io.klaude.tool.permission;

import static org.assertj.core.api.Assertions.assertThat;

import io.klaude.protocol.Event;
import io.klaude.protocol.ProtocolJson;
import io.klaude.tool.PermissionOutcome;
import io.klaude.tool.Tool;
import io.klaude.tool.ToolContext;
import io.klaude.tool.ToolResult;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class PermissionManagerTest {
    // 功能：验证四种用户决策及 always 决策在 pending 完成前持久化并跨 session 生效
    // 设计：对两个 ASK 工具依次 respond 四种 decision，读取 policy 文件并观察后续 auto outcome
    @Test
    void resolvesFourDecisionsAndPersistsAlwaysBeforeCompletion(@TempDir Path temp) throws Exception {
        List<Event> events = new ArrayList<>();
        PermissionPolicyStore store = new PermissionPolicyStore(temp.resolve("policy.toml"));
        Clock clock = Clock.fixed(Instant.parse("2026-07-19T10:15:30Z"), ZoneOffset.UTC);
        try (PermissionManager manager = new PermissionManager(
                Map.of(
                        "bash", ToolPolicy.ask(),
                        "write_file", ToolPolicy.ask()),
                store,
                event -> {
                    events.add(event);
                    return CompletableFuture.completedFuture(null);
                },
                clock,
                Duration.ofSeconds(5))) {
            Tool bash = fakeTool("bash");
            Tool write = fakeTool("write_file");

            var allowOnce = manager.check(
                    context(temp, "s1", "allow-once"), bash, params("command", "echo hi"));
            assertThat(manager.respond("allow-once", UserDecision.ALLOW_ONCE)).isTrue();
            assertThat(allowOnce.toCompletableFuture().get())
                    .isEqualTo(PermissionOutcome.allow("allow_once"));

            var denyOnce = manager.check(
                    context(temp, "s1", "deny-once"), bash, params("command", "echo no"));
            assertThat(manager.respond("deny-once", UserDecision.DENY_ONCE)).isTrue();
            assertThat(denyOnce.toCompletableFuture().get())
                    .isEqualTo(PermissionOutcome.deny("deny_once"));

            var alwaysAllow = manager.check(
                    context(temp, "s1", "always-allow"), bash, params("command", "echo yes"));
            assertThat(manager.respond("always-allow", UserDecision.ALWAYS_ALLOW)).isTrue();
            assertThat(alwaysAllow.toCompletableFuture().get())
                    .isEqualTo(PermissionOutcome.allow("always_allow"));
            assertThat(store.load()).containsEntry("bash", PolicyDecision.ALLOW);
            assertThat(manager.check(
                            context(temp, "s2", "auto-allow"), bash, params("command", "echo again"))
                    .toCompletableFuture().get())
                    .isEqualTo(PermissionOutcome.allow("auto_allow"));

            var alwaysDeny = manager.check(
                    context(temp, "s1", "always-deny"), write, params("path", "out.txt"));
            assertThat(manager.respond("always-deny", UserDecision.ALWAYS_DENY)).isTrue();
            assertThat(alwaysDeny.toCompletableFuture().get())
                    .isEqualTo(PermissionOutcome.deny("always_deny"));
            assertThat(store.load()).containsEntry("write_file", PolicyDecision.DENY);
            assertThat(manager.check(
                            context(temp, "s2", "auto-deny"), write, params("path", "other.txt"))
                    .toCompletableFuture().get())
                    .isEqualTo(PermissionOutcome.deny("auto_deny"));
        }

        assertThat(events).extracting(event -> event.getClass().getSimpleName())
                .contains("PermissionRequestedEvent", "PermissionGrantedEvent", "PermissionDeniedEvent");
    }

    // 功能：验证 permission timeout 拒绝请求、清理 pending 并忽略迟到响应
    // 设计：使用 30ms timeout 且不 respond，观察 timeout outcome、事件序列和 false 迟到响应
    @Test
    void timesOutAndCleansPendingRequest(@TempDir Path temp) throws Exception {
        List<Event> events = new java.util.concurrent.CopyOnWriteArrayList<>();
        PermissionPolicyStore store = new PermissionPolicyStore(temp.resolve("policy.toml"));
        try (PermissionManager manager = new PermissionManager(
                Map.of("bash", ToolPolicy.ask()),
                store,
                event -> {
                    events.add(event);
                    return CompletableFuture.completedFuture(null);
                },
                Clock.fixed(Instant.parse("2026-07-19T10:15:30Z"), ZoneOffset.UTC),
                Duration.ofMillis(30))) {
            var pending = manager.check(
                    context(temp, "session-001", "timeout-001"),
                    fakeTool("bash"),
                    params("command", "echo hi"));

            assertThat(pending.toCompletableFuture().get(
                            2, java.util.concurrent.TimeUnit.SECONDS))
                    .isEqualTo(PermissionOutcome.deny("timeout"));
            assertThat(manager.respond("timeout-001", UserDecision.ALLOW_ONCE)).isFalse();
            assertThat(events).extracting(event -> event.getClass().getSimpleName())
                    .containsExactly("PermissionRequestedEvent", "PermissionDeniedEvent");
        }
    }

    // 功能：验证 cancelSession 只拒绝目标 session 的 pending permission
    // 设计：同时创建 s1/s2 请求，取消 s2 后单独批准 s1 并对比两个 outcome
    @Test
    void cancelsOnlyPendingRequestsForTargetSession(@TempDir Path temp) throws Exception {
        PermissionPolicyStore store = new PermissionPolicyStore(temp.resolve("policy.toml"));
        try (PermissionManager manager = new PermissionManager(
                Map.of("bash", ToolPolicy.ask()),
                store,
                event -> CompletableFuture.completedFuture(null),
                Clock.systemUTC(),
                Duration.ofSeconds(5))) {
            Tool bash = fakeTool("bash");
            var sessionOne = manager.check(
                    context(temp, "s1", "tool-s1"), bash, params("command", "echo one"));
            var sessionTwo = manager.check(
                    context(temp, "s2", "tool-s2"), bash, params("command", "echo two"));

            manager.cancelSession("s2");

            assertThat(sessionTwo.toCompletableFuture().get())
                    .isEqualTo(PermissionOutcome.deny("deny_once"));
            assertThat(sessionOne.toCompletableFuture()).isNotDone();
            assertThat(manager.respond("tool-s1", UserDecision.ALLOW_ONCE)).isTrue();
            assertThat(sessionOne.toCompletableFuture().get())
                    .isEqualTo(PermissionOutcome.allow("allow_once"));
        }
    }

    // 功能：验证 bash outside-cwd 检查优先于已持久化的 always_allow
    // 设计：先对普通 bash 持久允许，再评估含绝对路径的命令并观察 ASK
    @Test
    void outsideWorkspaceCommandStillAsksAfterAlwaysAllow(@TempDir Path temp) throws Exception {
        PermissionPolicyStore store = new PermissionPolicyStore(temp.resolve("policy.toml"));
        try (PermissionManager manager = new PermissionManager(
                Map.of("bash", ToolPolicy.ask()),
                store,
                event -> CompletableFuture.completedFuture(null),
                Clock.systemUTC(),
                Duration.ofSeconds(5))) {
            Tool bash = fakeTool("bash");
            var first = manager.check(
                    context(temp, "s1", "always-allow"),
                    bash,
                    params("command", "echo safe"));
            assertThat(manager.respond("always-allow", UserDecision.ALWAYS_ALLOW)).isTrue();
            assertThat(first.toCompletableFuture().get())
                    .isEqualTo(PermissionOutcome.allow("always_allow"));

            assertThat(manager.evaluate("bash", params("command", "type C:\\outside.txt")))
                    .isEqualTo(PermissionAction.ASK);
        }
    }

    // 功能：验证零 permission timeout 表示无限等待而不是立即拒绝
    // 设计：用 Duration.ZERO 创建 pending，短暂等待后确认未完成再手动批准
    @Test
    void zeroTimeoutWaitsUntilExplicitResponse(@TempDir Path temp) throws Exception {
        PermissionPolicyStore store = new PermissionPolicyStore(temp.resolve("policy.toml"));
        try (PermissionManager manager = new PermissionManager(
                Map.of("bash", ToolPolicy.ask()),
                store,
                event -> CompletableFuture.completedFuture(null),
                Clock.systemUTC(),
                Duration.ZERO)) {
            var pending = manager.check(
                    context(temp, "s1", "no-timeout"),
                    fakeTool("bash"),
                    params("command", "echo waiting"));

            Thread.sleep(50);
            assertThat(pending.toCompletableFuture()).isNotDone();
            assertThat(manager.respond("no-timeout", UserDecision.ALLOW_ONCE)).isTrue();
            assertThat(pending.toCompletableFuture().get())
                    .isEqualTo(PermissionOutcome.allow("allow_once"));
        }
    }

    // 创建指定名称且无需实际执行的 fake tool
    private static Tool fakeTool(String name) {
        return new Tool() {
            // 返回 fake 工具名
            @Override
            public String name() {
                return name;
            }

            // 返回 fake 描述
            @Override
            public String description() {
                return name;
            }

            // 返回空 object schema
            @Override
            public com.fasterxml.jackson.databind.node.ObjectNode inputSchema() {
                return ProtocolJson.mapper().createObjectNode().put("type", "object");
            }

            // 返回固定成功结果
            @Override
            public java.util.concurrent.CompletionStage<ToolResult> execute(
                    ToolContext context,
                    com.fasterxml.jackson.databind.node.ObjectNode params) {
                return CompletableFuture.completedFuture(ToolResult.success("ok"));
            }
        };
    }

    // 创建一个隔离工具上下文
    private static ToolContext context(Path root, String sessionId, String toolUseId) {
        return new ToolContext(root, sessionId, "run-001", toolUseId);
    }

    // 创建一个单字段参数对象
    private static com.fasterxml.jackson.databind.node.ObjectNode params(String key, String value) {
        return ProtocolJson.mapper().createObjectNode().put(key, value);
    }
}
