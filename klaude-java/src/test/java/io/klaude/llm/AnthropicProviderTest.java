package io.klaude.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.klaude.protocol.Event;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class AnthropicProviderTest {
    // 功能：验证 Anthropic adapter 按 model、tokens、usage 顺序发布并拼接最终文本
    // 设计：fake stream client 依次发出 Hel/lo 和固定 usage，观察 LlmResponse 与协议事件
    @Test
    void streamsTokensAndPublishesUsageInOrder() throws Exception {
        AnthropicStreamClient client = (request, tokens) -> tokens.emit("Hel")
                .thenCompose(ignored -> tokens.emit("lo"))
                .thenApply(ignored -> new AnthropicStreamResult(
                        LlmStopReason.END_TURN,
                        List.of(),
                        List.of(),
                        new LlmUsage(100, 2, 10, 5, 0.0005)));
        var events = new CopyOnWriteArrayList<Event>();
        var provider = new AnthropicProvider(
                "test-model",
                client,
                Clock.fixed(Instant.parse("2026-07-19T12:00:00Z"), ZoneOffset.UTC),
                delay -> CompletableFuture.completedFuture(null));
        var request = new LlmRequest("run-001", 1, List.of(), List.of(), "system");

        LlmResponse response = provider.chat(
                        request,
                        event -> {
                            events.add(event);
                            return CompletableFuture.completedFuture(null);
                        })
                .toCompletableFuture().get();

        assertThat(response.stopReason()).isEqualTo(LlmStopReason.END_TURN);
        assertThat(response.text()).isEqualTo("Hello");
        assertThat(response.usage()).isEqualTo(new LlmUsage(100, 2, 10, 5, 0.0005));
        assertThat(events).extracting(event -> event.getClass().getSimpleName())
                .containsExactly(
                        "LlmModelSelectedEvent", "LlmTokenEvent", "LlmTokenEvent", "LlmUsageEvent");
    }

    // 功能：验证 transport retry 不重复发布已展示 token 且最终文本来自成功尝试
    // 设计：首轮发 token 后失败、第二轮成功，观察单次 backoff、单个 token event 和 final text
    @Test
    void retriesTransportFailureWithoutDuplicateTokens() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        AnthropicStreamClient client = (request, tokens) -> {
            if (attempts.incrementAndGet() == 1) {
                return tokens.emit("shown-once").thenCompose(ignored ->
                        CompletableFuture.failedFuture(
                                new AnthropicTransportException("stream dropped")));
            }
            return tokens.emit("final").thenApply(ignored -> new AnthropicStreamResult(
                    LlmStopReason.END_TURN,
                    List.of(),
                    List.of(),
                    new LlmUsage(10, 1, 0, 0, 0.00005)));
        };
        var delays = new CopyOnWriteArrayList<java.time.Duration>();
        var events = new CopyOnWriteArrayList<Event>();
        var provider = new AnthropicProvider(
                "test-model", client, Clock.systemUTC(), delay -> {
                    delays.add(delay);
                    return CompletableFuture.completedFuture(null);
                });

        LlmResponse response = provider.chat(
                        new LlmRequest("run-001", 1, List.of(), List.of(), "system"),
                        event -> {
                            events.add(event);
                            return CompletableFuture.completedFuture(null);
                        })
                .toCompletableFuture().get();

        assertThat(attempts).hasValue(2);
        assertThat(delays).containsExactly(java.time.Duration.ofSeconds(1));
        assertThat(response.text()).isEqualTo("final");
        assertThat(events).filteredOn(event -> event instanceof io.klaude.protocol.LlmTokenEvent)
                .singleElement().extracting(event -> ((io.klaude.protocol.LlmTokenEvent) event).token())
                .isEqualTo("shown-once");
    }

    // 功能：验证连续 transport 失败最多尝试三次并使用 1 秒、2 秒退避
    // 设计：fake client 每次都失败，观察原异常、调用次数和两段 delay，不允许第四次请求
    @Test
    void stopsAfterThreeTransportAttempts() {
        AtomicInteger attempts = new AtomicInteger();
        var failure = new AnthropicTransportException("still unavailable");
        AnthropicStreamClient client = (request, tokens) -> {
            attempts.incrementAndGet();
            return CompletableFuture.failedFuture(failure);
        };
        var delays = new CopyOnWriteArrayList<java.time.Duration>();
        var provider = new AnthropicProvider(
                "test-model", client, Clock.systemUTC(), delay -> {
                    delays.add(delay);
                    return CompletableFuture.completedFuture(null);
                });

        var completion = provider.chat(
                        new LlmRequest("run-001", 1, List.of(), List.of(), "system"),
                        event -> CompletableFuture.completedFuture(null))
                .toCompletableFuture();

        assertThatThrownBy(completion::join).hasRootCause(failure);
        assertThat(attempts).hasValue(3);
        assertThat(delays).containsExactly(
                java.time.Duration.ofSeconds(1), java.time.Duration.ofSeconds(2));
    }
}
