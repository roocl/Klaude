package io.klaude.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.klaude.agent.event.EventBus;
import io.klaude.llm.LlmResponse;
import io.klaude.llm.LlmStopReason;
import io.klaude.llm.ScriptedLlmProvider;
import io.klaude.protocol.Event;
import io.klaude.tool.ToolRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

final class AgentRunnerTest {
    // 功能：验证 runner 为成功 fake run 发布完整生命周期并返回 outcome
    // 设计：注入固定 run ID、clock 和 end_turn provider，观察事件顺序与结果
    @Test
    void publishesSuccessfulRunLifecycle() throws Exception {
        var provider = new ScriptedLlmProvider(List.of(new LlmResponse(
                LlmStopReason.END_TURN, List.of(), "done", null, List.of())));
        var events = new CopyOnWriteArrayList<Event>();
        var bus = new EventBus();
        bus.subscribe(event -> {
            events.add(event);
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        });
        AgentToolExecutor executor = call -> java.util.concurrent.CompletableFuture.completedFuture(
                io.klaude.tool.ToolResult.success("ok"));
        var runner = new AgentRunner(
                provider,
                new ToolRegistry(),
                executor,
                bus,
                Clock.fixed(Instant.parse("2026-07-19T12:00:00Z"), ZoneOffset.UTC),
                () -> "run-fixed",
                5);

        RunOutcome outcome = runner.run("finish task").toCompletableFuture().get();

        assertThat(outcome).isEqualTo(new RunOutcome("run-fixed", "success", "done", null, 1));
        assertThat(events).extracting(event -> event.getClass().getSimpleName())
                .containsExactly(
                        "RunStartedEvent", "StepStartedEvent",
                        "StepFinishedEvent", "RunFinishedEvent");
    }

    // 功能：验证 runner 在取消时先发布 failed run.finished 再传播取消
    // 设计：provider 返回 CancellationException，观察完成异常与最后生命周期事件
    @Test
    void publishesFinishedBeforePropagatingCancellation() {
        io.klaude.llm.LlmProvider provider = (request, sink) ->
                java.util.concurrent.CompletableFuture.failedFuture(
                        new java.util.concurrent.CancellationException("cancel"));
        var events = new CopyOnWriteArrayList<Event>();
        var bus = new EventBus();
        bus.subscribe(event -> {
            events.add(event);
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        });
        AgentToolExecutor executor = call -> java.util.concurrent.CompletableFuture.completedFuture(
                io.klaude.tool.ToolResult.success("ok"));
        var runner = new AgentRunner(
                provider, new ToolRegistry(), executor, bus, Clock.systemUTC(), () -> "run-fixed", 5);

        var completion = runner.run("cancel me").toCompletableFuture();

        assertThatThrownBy(completion::join)
                .hasRootCauseInstanceOf(java.util.concurrent.CancellationException.class);
        assertThat(events).extracting(event -> event.getClass().getSimpleName())
                .containsExactly("RunStartedEvent", "StepStartedEvent", "RunFinishedEvent");
        var finished = (io.klaude.protocol.RunFinishedEvent) events.getLast();
        assertThat(finished.status()).isEqualTo("failed");
        assertThat(finished.reason()).isEqualTo("cancelled");
    }

    // 功能：验证捕获式 run 使用固定 ID 和预填历史，并仅返回本轮新增 assistant 消息
    // 设计：scripted end_turn 配合一条历史 user 消息，通过公开 capture 观察 outcome 与增量消息
    @Test
    void capturesOnlyMessagesAddedBySessionRun() throws Exception {
        var provider = new ScriptedLlmProvider(List.of(new LlmResponse(
                LlmStopReason.END_TURN, List.of(), "session answer", null, List.of())));
        var runner = new AgentRunner(
                provider,
                new ToolRegistry(),
                call -> java.util.concurrent.CompletableFuture.completedFuture(
                        io.klaude.tool.ToolResult.success("ok")),
                new EventBus(),
                Clock.systemUTC(),
                () -> "unused",
                5);
        var history = io.klaude.protocol.ProtocolJson.mapper().createObjectNode()
                .put("role", "user")
                .put("content", "session question");

        AgentRunCapture capture = runner.runCaptured(new AgentRunRequest(
                        "run-fixed",
                        "session question",
                        List.of(history),
                        "remember",
                        "",
                        "",
                        null))
                .toCompletableFuture().get();

        assertThat(capture.outcome().runId()).isEqualTo("run-fixed");
        assertThat(capture.outcome().result()).isEqualTo("session answer");
        assertThat(capture.newMessages()).singleElement().satisfies(message -> {
            assertThat(message.path("role").asText()).isEqualTo("assistant");
            assertThat(message.path("content").get(0).path("text").asText())
                    .isEqualTo("session answer");
        });
    }
}
