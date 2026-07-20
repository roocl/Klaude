package io.klaude.tool;

import static org.assertj.core.api.Assertions.assertThat;

import io.klaude.protocol.Event;
import io.klaude.protocol.ProtocolJson;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ToolInvokerTest {
    // 功能：验证 schema invalid 在 permission 与工具执行前失败并发布 started/failed
    // 设计：用两个计数器观察 permission gateway 和 tool body，传缺少 required path 的参数
    @Test
    void rejectsInvalidSchemaBeforePermissionAndExecution(@TempDir Path temp) throws Exception {
        AtomicInteger permissionCalls = new AtomicInteger();
        AtomicInteger executionCalls = new AtomicInteger();
        List<Event> events = new ArrayList<>();
        ToolRegistry registry = new ToolRegistry();
        registry.register(new Tool() {
            // 返回测试工具名
            @Override
            public String name() {
                return "read_file";
            }

            // 返回测试描述
            @Override
            public String description() {
                return "Read one file";
            }

            // 返回要求 path string 的 schema
            @Override
            public com.fasterxml.jackson.databind.node.ObjectNode inputSchema() {
                var mapper = ProtocolJson.mapper();
                var schema = mapper.createObjectNode().put("type", "object");
                schema.set("properties", mapper.createObjectNode().set(
                        "path", mapper.createObjectNode().put("type", "string")));
                schema.set("required", mapper.createArrayNode().add("path"));
                return schema;
            }

            // 记录工具主体是否被错误调用
            @Override
            public java.util.concurrent.CompletionStage<ToolResult> execute(
                    ToolContext context,
                    com.fasterxml.jackson.databind.node.ObjectNode params) {
                executionCalls.incrementAndGet();
                return CompletableFuture.completedFuture(ToolResult.success("unexpected"));
            }
        });
        PermissionGateway permissions = (context, tool, params) -> {
            permissionCalls.incrementAndGet();
            return CompletableFuture.completedFuture(PermissionOutcome.allow("auto_allow"));
        };
        ToolEventSink sink = event -> {
            events.add(event);
            return CompletableFuture.completedFuture(null);
        };
        Clock clock = Clock.fixed(Instant.parse("2026-07-19T10:15:30Z"), ZoneOffset.UTC);
        try (ToolInvoker invoker = new ToolInvoker(registry, permissions, sink, clock)) {
            ToolResult result = invoker.invoke(
                            new ToolContext(temp, "session-001", "run-001", "tool-001"),
                            "read_file",
                            ProtocolJson.mapper().createObjectNode(),
                            Duration.ofSeconds(1))
                    .toCompletableFuture().get();

            assertThat(result.isError()).isTrue();
            assertThat(result.errorType()).isEqualTo(ToolErrorType.SCHEMA_ERROR);
            assertThat(permissionCalls).hasValue(0);
            assertThat(executionCalls).hasValue(0);
            assertThat(events).extracting(event -> event.getClass().getSimpleName())
                    .containsExactly("ToolCallStartedEvent", "ToolCallFailedEvent");
        }
    }

    // 功能：验证授权工具在 virtual thread 执行并按 started/finished 顺序发布成功事件
    // 设计：fake tool 记录 Thread.isVirtual，返回 Unicode 内容，等待 invocation completion 后观察结果与事件
    @Test
    void executesOnVirtualThreadAndEmitsFinished(@TempDir Path temp) throws Exception {
        AtomicInteger virtualExecutions = new AtomicInteger();
        List<Event> events = new ArrayList<>();
        ToolRegistry registry = new ToolRegistry();
        registry.register(new Tool() {
            // 返回成功工具名
            @Override
            public String name() {
                return "echo";
            }

            // 返回成功工具描述
            @Override
            public String description() {
                return "Echo text";
            }

            // 返回要求 text string 的 schema
            @Override
            public com.fasterxml.jackson.databind.node.ObjectNode inputSchema() {
                var mapper = ProtocolJson.mapper();
                var schema = mapper.createObjectNode().put("type", "object");
                schema.set("properties", mapper.createObjectNode().set(
                        "text", mapper.createObjectNode().put("type", "string")));
                schema.set("required", mapper.createArrayNode().add("text"));
                return schema;
            }

            // 在执行时记录 virtual thread 并回显参数
            @Override
            public java.util.concurrent.CompletionStage<ToolResult> execute(
                    ToolContext context,
                    com.fasterxml.jackson.databind.node.ObjectNode params) {
                if (Thread.currentThread().isVirtual()) {
                    virtualExecutions.incrementAndGet();
                }
                return CompletableFuture.completedFuture(
                        ToolResult.success(params.path("text").asText()));
            }
        });
        ToolEventSink sink = event -> {
            events.add(event);
            return CompletableFuture.completedFuture(null);
        };
        try (ToolInvoker invoker = new ToolInvoker(
                registry,
                (context, tool, params) -> CompletableFuture.completedFuture(
                        PermissionOutcome.allow("auto_allow")),
                sink,
                Clock.systemUTC())) {
            ToolResult result = invoker.invoke(
                            new ToolContext(temp, "session-001", "run-001", "tool-001"),
                            "echo",
                            ProtocolJson.mapper().createObjectNode().put("text", "你好"),
                            Duration.ofSeconds(1))
                    .toCompletableFuture().get();

            assertThat(result).isEqualTo(ToolResult.success("你好"));
            assertThat(virtualExecutions).hasValue(1);
            assertThat(events).extracting(event -> event.getClass().getSimpleName())
                    .containsExactly("ToolCallStartedEvent", "ToolCallFinishedEvent");
        }
    }

    // 功能：验证超时会中断 virtual-thread 工具主体并仅返回 timeout failed 事件
    // 设计：blocking tool 睡眠一分钟，50ms timeout 后观察 interrupt flag、结果分类和事件序列
    @Test
    void interruptsBlockingToolOnTimeout(@TempDir Path temp) throws Exception {
        java.util.concurrent.atomic.AtomicBoolean interrupted = new java.util.concurrent.atomic.AtomicBoolean();
        List<Event> events = new java.util.concurrent.CopyOnWriteArrayList<>();
        ToolRegistry registry = new ToolRegistry();
        registry.register(new Tool() {
            // 返回慢工具名
            @Override
            public String name() {
                return "slow";
            }

            // 返回慢工具描述
            @Override
            public String description() {
                return "Blocks until interrupted";
            }

            // 返回空 object schema
            @Override
            public com.fasterxml.jackson.databind.node.ObjectNode inputSchema() {
                return ProtocolJson.mapper().createObjectNode().put("type", "object");
            }

            // 阻塞当前 virtual thread 并记录 timeout interrupt
            @Override
            public java.util.concurrent.CompletionStage<ToolResult> execute(
                    ToolContext context,
                    com.fasterxml.jackson.databind.node.ObjectNode params) {
                try {
                    Thread.sleep(60_000);
                    return CompletableFuture.completedFuture(ToolResult.success("unexpected"));
                } catch (InterruptedException error) {
                    interrupted.set(true);
                    Thread.currentThread().interrupt();
                    return CompletableFuture.completedFuture(
                            ToolResult.failure("interrupted", ToolErrorType.RUNTIME_ERROR));
                }
            }
        });
        try (ToolInvoker invoker = new ToolInvoker(
                registry,
                (context, tool, params) -> CompletableFuture.completedFuture(
                        PermissionOutcome.allow("auto_allow")),
                event -> {
                    events.add(event);
                    return CompletableFuture.completedFuture(null);
                },
                Clock.systemUTC())) {
            ToolResult result = invoker.invoke(
                            new ToolContext(temp, "session-001", "run-001", "tool-001"),
                            "slow",
                            ProtocolJson.mapper().createObjectNode(),
                            Duration.ofMillis(50))
                    .toCompletableFuture().get(5, java.util.concurrent.TimeUnit.SECONDS);

            for (int attempt = 0; attempt < 50 && !interrupted.get(); attempt++) {
                Thread.sleep(10);
            }
            assertThat(result.errorType()).isEqualTo(ToolErrorType.TIMEOUT);
            assertThat(interrupted).isTrue();
            assertThat(events).extracting(event -> event.getClass().getSimpleName())
                    .containsExactly("ToolCallStartedEvent", "ToolCallFailedEvent");
        }
    }

    // 功能：验证 permission deny 返回拒绝结果且绝不执行工具主体
    // 设计：用计数 fake tool 和固定 deny gateway 调用 invoker，观察零次执行与 failed 事件
    @Test
    void neverExecutesToolAfterPermissionDenial(@TempDir Path temp) throws Exception {
        AtomicInteger executionCalls = new AtomicInteger();
        List<Event> events = new ArrayList<>();
        ToolRegistry registry = new ToolRegistry();
        registry.register(new Tool() {
            // 返回被拒绝工具名
            @Override
            public String name() {
                return "write_file";
            }

            // 返回被拒绝工具描述
            @Override
            public String description() {
                return "Must not execute";
            }

            // 返回空 object schema
            @Override
            public com.fasterxml.jackson.databind.node.ObjectNode inputSchema() {
                return ProtocolJson.mapper().createObjectNode().put("type", "object");
            }

            // 记录工具主体是否被错误调用
            @Override
            public java.util.concurrent.CompletionStage<ToolResult> execute(
                    ToolContext context,
                    com.fasterxml.jackson.databind.node.ObjectNode params) {
                executionCalls.incrementAndGet();
                return CompletableFuture.completedFuture(ToolResult.success("unexpected"));
            }
        });
        try (ToolInvoker invoker = new ToolInvoker(
                registry,
                (context, tool, params) -> CompletableFuture.completedFuture(
                        PermissionOutcome.deny("deny_once")),
                event -> {
                    events.add(event);
                    return CompletableFuture.completedFuture(null);
                },
                Clock.systemUTC())) {
            ToolResult result = invoker.invoke(
                            new ToolContext(temp, "session-001", "run-001", "tool-001"),
                            "write_file",
                            ProtocolJson.mapper().createObjectNode(),
                            Duration.ofSeconds(1))
                    .toCompletableFuture().get();

            assertThat(result.errorType()).isEqualTo(ToolErrorType.PERMISSION_DENIED);
            assertThat(executionCalls).hasValue(0);
            assertThat(events).extracting(event -> event.getClass().getSimpleName())
                    .containsExactly("ToolCallStartedEvent", "ToolCallFailedEvent");
        }
    }

    // 功能：验证工具主体抛出同步异常时转为 runtime_error 和 failed 事件
    // 设计：注册直接抛异常的 fake tool，自动授权后观察 ToolResult 与事件序列
    @Test
    void convertsToolExceptionToRuntimeFailure(@TempDir Path temp) throws Exception {
        List<Event> events = new ArrayList<>();
        ToolRegistry registry = new ToolRegistry();
        registry.register(new Tool() {
            // 返回异常工具名
            @Override
            public String name() {
                return "explode";
            }

            // 返回异常工具描述
            @Override
            public String description() {
                return "Throws synchronously";
            }

            // 返回空 object schema
            @Override
            public com.fasterxml.jackson.databind.node.ObjectNode inputSchema() {
                return ProtocolJson.mapper().createObjectNode().put("type", "object");
            }

            // 抛出可观察的同步工具异常
            @Override
            public java.util.concurrent.CompletionStage<ToolResult> execute(
                    ToolContext context,
                    com.fasterxml.jackson.databind.node.ObjectNode params) {
                throw new IllegalStateException("tool exploded");
            }
        });
        try (ToolInvoker invoker = new ToolInvoker(
                registry,
                (context, tool, params) -> CompletableFuture.completedFuture(
                        PermissionOutcome.allow("auto_allow")),
                event -> {
                    events.add(event);
                    return CompletableFuture.completedFuture(null);
                },
                Clock.systemUTC())) {
            ToolResult result = invoker.invoke(
                            new ToolContext(temp, "session-001", "run-001", "tool-001"),
                            "explode",
                            ProtocolJson.mapper().createObjectNode(),
                            Duration.ofSeconds(1))
                    .toCompletableFuture().get();

            assertThat(result.errorType()).isEqualTo(ToolErrorType.RUNTIME_ERROR);
            assertThat(result.content()).isEqualTo("tool exploded");
            assertThat(events).extracting(event -> event.getClass().getSimpleName())
                    .containsExactly("ToolCallStartedEvent", "ToolCallFailedEvent");
        }
    }
}
