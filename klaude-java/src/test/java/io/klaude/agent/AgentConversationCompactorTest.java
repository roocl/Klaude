package io.klaude.agent;

import static org.assertj.core.api.Assertions.assertThat;

import io.klaude.agent.event.EventBus;
import io.klaude.llm.LlmResponse;
import io.klaude.llm.LlmStopReason;
import io.klaude.llm.LlmUsage;
import io.klaude.llm.ScriptedLlmProvider;
import io.klaude.protocol.Event;
import io.klaude.protocol.ProtocolJson;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class AgentConversationCompactorTest {
    // 功能：验证 agent compactor 用 provider 摘要替换合法消息、写文件并发布 compacted 事件
    // 设计：scripted summary、固定 Clock、临时 session 目录和真实 EventBus 观察全部公开结果
    @Test
    void compactsContextAndPublishesEvent(@TempDir Path temp) throws Exception {
        var provider = new ScriptedLlmProvider(List.of(new LlmResponse(
                LlmStopReason.END_TURN,
                List.of(),
                "## Summary\nstate",
                new LlmUsage(100, 7, 0, 0, 0.1),
                List.of())));
        var events = new CopyOnWriteArrayList<Event>();
        var bus = new EventBus();
        bus.subscribe(event -> {
            events.add(event);
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        });
        Clock clock = Clock.fixed(Instant.parse("2026-07-19T14:00:00Z"), ZoneOffset.UTC);
        var compactor = new AgentConversationCompactor(
                provider, bus, temp, "sess-fixed", clock);
        var first = ProtocolJson.mapper().createObjectNode()
                .put("role", "user").put("content", "x".repeat(100));
        var second = ProtocolJson.mapper().createObjectNode()
                .put("role", "assistant").put("content", "y".repeat(100));
        var context = new ExecutionContext(
                "run-fixed", "goal", 5, List.of(first, second));

        compactor.compact(context).toCompletableFuture().get();

        assertThat(context.messages()).extracting(message -> message.path("role").asText())
                .containsExactly("user", "assistant");
        assertThat(context.messages().getFirst().path("content").asText())
                .isEqualTo("## Summary\nstate");
        assertThat(temp.resolve("summary_20260719_140000.md"))
                .content(java.nio.charset.StandardCharsets.UTF_8)
                .isEqualTo("## Summary\nstate");
        assertThat(events).singleElement()
                .isInstanceOf(io.klaude.protocol.ContextCompactedEvent.class);
    }

    // 功能：验证摘要 provider 失败时 context、事件和文件均保持不变
    // 设计：注入 failed provider 并保存调用前消息副本，compact 完成后比较全部可观察状态
    @Test
    void preservesContextWhenProviderFails(@TempDir Path temp) throws Exception {
        io.klaude.llm.LlmProvider provider = (request, events) ->
                java.util.concurrent.CompletableFuture.failedFuture(
                        new IllegalStateException("summary failed"));
        var observed = new CopyOnWriteArrayList<Event>();
        var bus = new EventBus();
        bus.subscribe(event -> {
            observed.add(event);
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        });
        var compactor = new AgentConversationCompactor(
                provider, bus, temp, "sess-fixed", Clock.systemUTC());
        var message = ProtocolJson.mapper().createObjectNode()
                .put("role", "user").put("content", "original");
        var context = new ExecutionContext("run-fixed", "goal", 5, List.of(message));
        var before = context.messages();

        compactor.compact(context).toCompletableFuture().get();

        assertThat(context.messages()).isEqualTo(before);
        assertThat(observed).isEmpty();
        try (var files = java.nio.file.Files.list(temp)) {
            assertThat(files).isEmpty();
        }
    }
}
