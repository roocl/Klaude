package io.klaude.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.klaude.agent.event.EventBus;
import io.klaude.llm.LlmResponse;
import io.klaude.llm.LlmStopReason;
import io.klaude.llm.LlmToolCall;
import io.klaude.llm.ScriptedLlmProvider;
import io.klaude.protocol.Event;
import io.klaude.tool.ToolRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class AgentLoopTest {
    // 功能：验证 end_turn 响应在单步内完成 run 并发布一对 step 事件
    // 设计：注入固定时钟和单条 scripted response，观察 context 结果、step 与事件顺序
    @Test
    void completesSingleEndTurnStep() throws Exception {
        var provider = new ScriptedLlmProvider(List.of(new LlmResponse(
                LlmStopReason.END_TURN, List.of(), "任务完成", null, List.of())));
        var events = new CopyOnWriteArrayList<Event>();
        var bus = new EventBus();
        bus.subscribe(event -> {
            events.add(event);
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        });
        var loop = new AgentLoop(
                provider,
                new ToolRegistry(),
                bus,
                Clock.fixed(Instant.parse("2026-07-19T12:00:00Z"), ZoneOffset.UTC));
        var context = new ExecutionContext("run-001", "完成迁移", 5);

        loop.run(context).toCompletableFuture().get();

        assertThat(context.status()).isEqualTo("success");
        assertThat(context.result()).isEqualTo("任务完成");
        assertThat(context.step()).isEqualTo(1);
        assertThat(events).extracting(event -> event.getClass().getSimpleName())
                .containsExactly("StepStartedEvent", "StepFinishedEvent");
    }

    // 功能：验证 end_turn 与 max steps 同一步发生时由成功终止优先
    // 设计：将 maxSteps 设为 1 并在第一步返回 end_turn，观察结果不被步数失败覆盖
    @Test
    void endTurnWinsAtMaximumStep() throws Exception {
        var provider = new ScriptedLlmProvider(List.of(new LlmResponse(
                LlmStopReason.END_TURN, List.of(), "done", null, List.of())));
        var context = new ExecutionContext("run-001", "finish now", 1);

        new AgentLoop(provider, new ToolRegistry(), new EventBus(), Clock.systemUTC())
                .run(context).toCompletableFuture().get();

        assertThat(context.status()).isEqualTo("success");
        assertThat(context.result()).isEqualTo("done");
        assertThat(context.reason()).isNull();
        assertThat(context.step()).isEqualTo(1);
    }

    // 功能：验证 tool_use 结果进入 context 后 loop 继续到 end_turn
    // 设计：脚本返回一次 echo 调用和最终文本，观察两步事件、一次执行与消息配平
    @Test
    void executesToolThenContinuesToEndTurn() throws Exception {
        var call = new LlmToolCall(
                "tool-001",
                "echo",
                io.klaude.protocol.ProtocolJson.mapper().createObjectNode().put("text", "你好"));
        var provider = new ScriptedLlmProvider(List.of(
                new LlmResponse(LlmStopReason.TOOL_USE, List.of(call), "", null, List.of()),
                new LlmResponse(LlmStopReason.END_TURN, List.of(), "已完成", null, List.of())));
        var events = new CopyOnWriteArrayList<Event>();
        var bus = new EventBus();
        bus.subscribe(event -> {
            events.add(event);
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        });
        AtomicInteger executions = new AtomicInteger();
        AgentToolExecutor executor = requested -> {
            executions.incrementAndGet();
            return java.util.concurrent.CompletableFuture.completedFuture(
                    io.klaude.tool.ToolResult.success(requested.input().path("text").asText()));
        };
        var loop = new AgentLoop(
                provider, new ToolRegistry(), executor, bus, Clock.systemUTC());
        var context = new ExecutionContext("run-001", "问候", 5);

        loop.run(context).toCompletableFuture().get();

        assertThat(context.status()).isEqualTo("success");
        assertThat(context.step()).isEqualTo(2);
        assertThat(executions).hasValue(1);
        assertThat(context.messages()).extracting(message -> message.path("role").asText())
                .containsExactly("user", "assistant", "user", "assistant");
        var resultBlock = context.messages().get(2).path("content").get(0);
        assertThat(resultBlock.path("tool_use_id").asText()).isEqualTo("tool-001");
        assertThat(resultBlock.path("content").asText()).isEqualTo("你好");
        assertThat(events).extracting(event -> event.getClass().getSimpleName())
                .containsExactly(
                        "StepStartedEvent", "StepFinishedEvent",
                        "StepStartedEvent", "StepFinishedEvent");
    }

    // 功能：验证工具返回错误结果后 loop 仍将观察结果交给 provider 并继续
    // 设计：首步工具固定返回 failure，第二步 scripted end_turn，观察错误配平与最终成功
    @Test
    void continuesAfterToolFailureResult() throws Exception {
        var call = new LlmToolCall(
                "tool-001", "failing", io.klaude.protocol.ProtocolJson.mapper().createObjectNode());
        var provider = new ScriptedLlmProvider(List.of(
                new LlmResponse(LlmStopReason.TOOL_USE, List.of(call), "", null, List.of()),
                new LlmResponse(LlmStopReason.END_TURN, List.of(), "recovered", null, List.of())));
        AgentToolExecutor executor = requested -> java.util.concurrent.CompletableFuture.completedFuture(
                io.klaude.tool.ToolResult.failure(
                        "tool failed", io.klaude.tool.ToolErrorType.RUNTIME_ERROR));
        var context = new ExecutionContext("run-001", "recover", 3);

        new AgentLoop(provider, new ToolRegistry(), executor, new EventBus(), Clock.systemUTC())
                .run(context).toCompletableFuture().get();

        assertThat(context.status()).isEqualTo("success");
        assertThat(context.result()).isEqualTo("recovered");
        var result = context.messages().get(2).path("content").get(0);
        assertThat(result.path("is_error").asBoolean()).isTrue();
        assertThat(result.path("content").asText()).isEqualTo("tool failed");
    }

    // 功能：验证连续 tool_use 达到 max steps 时以 exceeded_max_steps 失败
    // 设计：提供两个 scripted tool response 和 maxSteps=2，观察两步配对后终止
    @Test
    void failsAfterMaximumSteps() throws Exception {
        var call = new LlmToolCall(
                "tool-001", "echo", io.klaude.protocol.ProtocolJson.mapper().createObjectNode());
        var provider = new ScriptedLlmProvider(List.of(
                new LlmResponse(LlmStopReason.TOOL_USE, List.of(call), "", null, List.of()),
                new LlmResponse(LlmStopReason.TOOL_USE, List.of(call), "", null, List.of())));
        var events = new CopyOnWriteArrayList<Event>();
        var bus = new EventBus();
        bus.subscribe(event -> {
            events.add(event);
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        });
        AgentToolExecutor executor = requested -> java.util.concurrent.CompletableFuture.completedFuture(
                io.klaude.tool.ToolResult.success("ok"));
        var context = new ExecutionContext("run-001", "loop", 2);

        new AgentLoop(provider, new ToolRegistry(), executor, bus, Clock.systemUTC())
                .run(context).toCompletableFuture().get();

        assertThat(context.status()).isEqualTo("failed");
        assertThat(context.reason()).isEqualTo("exceeded_max_steps");
        assertThat(context.step()).isEqualTo(2);
        assertThat(events).extracting(event -> event.getClass().getSimpleName())
                .containsExactly(
                        "StepStartedEvent", "StepFinishedEvent",
                        "StepStartedEvent", "StepFinishedEvent");
    }

    // 功能：验证 max_tokens 中断的工具调用会生成配对错误结果而不执行
    // 设计：返回含两个 tool call 的 max_tokens 后 end_turn，观察零执行和合并错误 blocks
    @Test
    void balancesTruncatedToolCallsWithoutExecution() throws Exception {
        var first = new LlmToolCall(
                "tool-001", "echo", io.klaude.protocol.ProtocolJson.mapper().createObjectNode());
        var second = new LlmToolCall(
                "tool-002", "echo", io.klaude.protocol.ProtocolJson.mapper().createObjectNode());
        var provider = new ScriptedLlmProvider(List.of(
                new LlmResponse(
                        LlmStopReason.MAX_TOKENS, List.of(first, second), "", null, List.of()),
                new LlmResponse(LlmStopReason.END_TURN, List.of(), "recovered", null, List.of())));
        AtomicInteger executions = new AtomicInteger();
        AgentToolExecutor executor = requested -> {
            executions.incrementAndGet();
            return java.util.concurrent.CompletableFuture.completedFuture(
                    io.klaude.tool.ToolResult.success("unexpected"));
        };
        var context = new ExecutionContext("run-001", "large task", 3);

        new AgentLoop(provider, new ToolRegistry(), executor, new EventBus(), Clock.systemUTC())
                .run(context).toCompletableFuture().get();

        assertThat(context.status()).isEqualTo("success");
        assertThat(executions).hasValue(0);
        var results = context.messages().get(2).path("content");
        assertThat(results).hasSize(2);
        assertThat(results.get(0).path("tool_use_id").asText()).isEqualTo("tool-001");
        assertThat(results.get(1).path("tool_use_id").asText()).isEqualTo("tool-002");
        assertThat(results.get(0).path("is_error").asBoolean()).isTrue();
        assertThat(results.get(0).path("content").asText()).contains("output token limit");
    }

    // 功能：验证 provider 异常被吸收并将 context 标记为 llm_error
    // 设计：注入返回 failed future 的 provider，观察 run 不抛异常且未完成 step 仅发布 started
    @Test
    void marksLlmFailureWithoutPropagating() throws Exception {
        io.klaude.llm.LlmProvider provider = (request, sink) ->
                java.util.concurrent.CompletableFuture.failedFuture(
                        new IllegalStateException("provider unavailable"));
        var events = new CopyOnWriteArrayList<Event>();
        var bus = new EventBus();
        bus.subscribe(event -> {
            events.add(event);
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        });
        var context = new ExecutionContext("run-001", "goal", 3);

        new AgentLoop(provider, new ToolRegistry(), bus, Clock.systemUTC())
                .run(context).toCompletableFuture().get();

        assertThat(context.status()).isEqualTo("failed");
        assertThat(context.reason()).isEqualTo("llm_error");
        assertThat(events).extracting(event -> event.getClass().getSimpleName())
                .containsExactly("StepStartedEvent");
    }

    // 功能：验证 provider 取消先标记 context 再向上传播取消信号
    // 设计：注入 CancellationException failed future，观察 completion 失败和 cancelled reason
    @Test
    void marksCancelledAndPropagatesSignal() {
        io.klaude.llm.LlmProvider provider = (request, sink) ->
                java.util.concurrent.CompletableFuture.failedFuture(
                        new java.util.concurrent.CancellationException("cancel run"));
        var context = new ExecutionContext("run-001", "goal", 3);

        var completion = new AgentLoop(
                        provider, new ToolRegistry(), new EventBus(), Clock.systemUTC())
                .run(context).toCompletableFuture();

        assertThat(completion).isCompletedExceptionally();
        assertThatThrownBy(completion::join)
                .hasRootCauseInstanceOf(java.util.concurrent.CancellationException.class);
        assertThat(context.status()).isEqualTo("failed");
        assertThat(context.reason()).isEqualTo("cancelled");
    }

    // 功能：验证同一响应中的多个工具调用严格串行执行
    // 设计：第一个 executor future 人为挂起，确认第二个未启动后再释放并观察顺序
    @Test
    void executesMultipleToolsSerially() throws Exception {
        var first = new LlmToolCall(
                "tool-001", "first", io.klaude.protocol.ProtocolJson.mapper().createObjectNode());
        var second = new LlmToolCall(
                "tool-002", "second", io.klaude.protocol.ProtocolJson.mapper().createObjectNode());
        var provider = new ScriptedLlmProvider(List.of(
                new LlmResponse(LlmStopReason.TOOL_USE, List.of(first, second), "", null, List.of()),
                new LlmResponse(LlmStopReason.END_TURN, List.of(), "done", null, List.of())));
        var firstResult = new java.util.concurrent.CompletableFuture<io.klaude.tool.ToolResult>();
        var invocations = new CopyOnWriteArrayList<String>();
        AgentToolExecutor executor = call -> {
            invocations.add(call.name());
            return call.name().equals("first")
                    ? firstResult
                    : java.util.concurrent.CompletableFuture.completedFuture(
                            io.klaude.tool.ToolResult.success("second-result"));
        };
        var context = new ExecutionContext("run-001", "serial", 3);

        var completion = new AgentLoop(
                        provider, new ToolRegistry(), executor, new EventBus(), Clock.systemUTC())
                .run(context).toCompletableFuture();
        for (int attempt = 0; attempt < 100 && invocations.isEmpty(); attempt++) {
            Thread.sleep(10);
        }
        assertThat(invocations).containsExactly("first");
        firstResult.complete(io.klaude.tool.ToolResult.success("first-result"));
        completion.get();

        assertThat(invocations).containsExactly("first", "second");
        assertThat(context.status()).isEqualTo("success");
    }

    // 功能：验证 assistant blocks 始终按 thinking、text、tool_use 顺序保存
    // 设计：一步响应同时包含三种 block，执行后直接观察 context JSON 顺序与签名
    @Test
    void preservesThinkingBeforeTextAndToolUse() throws Exception {
        var thinking = io.klaude.protocol.ProtocolJson.mapper().createObjectNode()
                .put("type", "thinking")
                .put("thinking", "plan")
                .put("signature", "sig");
        var call = new LlmToolCall(
                "tool-001", "echo", io.klaude.protocol.ProtocolJson.mapper().createObjectNode());
        var provider = new ScriptedLlmProvider(List.of(
                new LlmResponse(
                        LlmStopReason.TOOL_USE, List.of(call), "working", null, List.of(thinking)),
                new LlmResponse(LlmStopReason.END_TURN, List.of(), "done", null, List.of())));
        AgentToolExecutor executor = requested -> java.util.concurrent.CompletableFuture.completedFuture(
                io.klaude.tool.ToolResult.success("ok"));
        var context = new ExecutionContext("run-001", "think", 3);

        new AgentLoop(provider, new ToolRegistry(), executor, new EventBus(), Clock.systemUTC())
                .run(context).toCompletableFuture().get();

        var blocks = context.messages().get(1).path("content");
        assertThat(blocks).extracting(block -> block.path("type").asText())
                .containsExactly("thinking", "text", "tool_use");
        assertThat(blocks.get(0).path("thinking").asText()).isEqualTo("plan");
        assertThat(blocks.get(0).path("signature").asText()).isEqualTo("sig");
    }

    // 功能：验证 AgentLoop 将 ExecutionContext 的分层 system prompt 传给 provider
    // 设计：fake provider 捕获 request.system，context 注入 notes 后执行单步 end_turn 并观察内容
    @Test
    void sendsLayeredContextSystemPromptToProvider() throws Exception {
        var captured = new java.util.concurrent.atomic.AtomicReference<String>();
        io.klaude.llm.LlmProvider provider = (request, sink) -> {
            captured.set(request.systemPrompt());
            return java.util.concurrent.CompletableFuture.completedFuture(new LlmResponse(
                    LlmStopReason.END_TURN, List.of(), "done", null, List.of()));
        };
        var context = new ExecutionContext(
                "run-001", "goal", 1, List.of(), "remember this", "", "", null);

        new AgentLoop(provider, new ToolRegistry(), new EventBus(), Clock.systemUTC())
                .run(context).toCompletableFuture().get();

        assertThat(captured.get()).contains("## Session Notes\nremember this");
    }

    // 功能：验证 tool_use 步的 context usage 等于阈值时触发一次自动 compact
    // 设计：首响应 usage=0.8 并调用工具，注入计数 compactor 后以第二步 end_turn 收尾
    @Test
    void autoCompactsAtConfiguredUsageThreshold() throws Exception {
        var call = new LlmToolCall(
                "tool-001", "echo", io.klaude.protocol.ProtocolJson.mapper().createObjectNode());
        var provider = new ScriptedLlmProvider(List.of(
                new LlmResponse(
                        LlmStopReason.TOOL_USE,
                        List.of(call),
                        "",
                        new io.klaude.llm.LlmUsage(80, 1, 0, 0, 0.8),
                        List.of()),
                new LlmResponse(LlmStopReason.END_TURN, List.of(), "done", null, List.of())));
        AgentToolExecutor executor = requested -> java.util.concurrent.CompletableFuture.completedFuture(
                io.klaude.tool.ToolResult.success("ok"));
        var compactions = new AtomicInteger();
        AgentCompactor compactor = context -> {
            compactions.incrementAndGet();
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        };
        var context = new ExecutionContext("run-001", "compact", 3);

        new AgentLoop(
                provider,
                new ToolRegistry(),
                executor,
                new EventBus(),
                Clock.systemUTC(),
                compactor,
                0.8)
                .run(context).toCompletableFuture().get();

        assertThat(compactions).hasValue(1);
        assertThat(context.status()).isEqualTo("success");
    }
}
