package io.klaude.llm;

import io.klaude.protocol.LlmModelSelectedEvent;
import io.klaude.protocol.LlmTokenEvent;
import io.klaude.protocol.LlmUsageEvent;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public final class AnthropicProvider implements LlmProvider {
    private static final int MAX_TOKENS = 8192;
    private final String model;
    private final AnthropicStreamClient client;
    private final Clock clock;
    private final RetryDelay retryDelay;

    // 初始化 model、流式 client、时钟和 retry delay 边界
    public AnthropicProvider(
            String model,
            AnthropicStreamClient client,
            Clock clock,
            RetryDelay retryDelay) {
        this.model = java.util.Objects.requireNonNull(model, "model");
        this.client = java.util.Objects.requireNonNull(client, "client");
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
        this.retryDelay = java.util.Objects.requireNonNull(retryDelay, "retryDelay");
    }

    // 流式生成 LLM 响应并发布 model、token 和 usage 事件
    @Override
    public CompletionStage<LlmResponse> chat(LlmRequest request, LlmEventSink events) {
        var selected = new LlmModelSelectedEvent(
                request.runId(), model, "static", Instant.now(clock).toString());
        return events.emit(selected)
                .thenCompose(ignored -> streamAttempt(request, events, 1))
                .thenCompose(attempt -> emitUsage(request, attempt.result().usage(), events)
                        .thenApply(ignored -> new LlmResponse(
                                attempt.result().stopReason(),
                                attempt.result().toolCalls(),
                                attempt.text(),
                                attempt.result().usage(),
                                attempt.result().thinkingBlocks())));
    }

    // 执行一次 stream attempt 并对可 retry transport 错误应用有界 backoff
    private CompletionStage<SuccessfulAttempt> streamAttempt(
            LlmRequest request, LlmEventSink events, int attempt) {
        var text = new StringBuilder();
        CompletionStage<AnthropicStreamResult> stream;
        try {
            stream = client.stream(
                    new AnthropicStreamRequest(model, request, MAX_TOKENS),
                    token -> {
                        text.append(token);
                        if (attempt != 1) {
                            return CompletableFuture.completedFuture(null);
                        }
                        return events.emit(new LlmTokenEvent(
                                request.runId(), token, Instant.now(clock).toString()));
                    });
        } catch (Throwable error) {
            stream = CompletableFuture.failedFuture(error);
        }
        return stream.handle((result, error) -> new StreamOutcome(result, error))
                .thenCompose(outcome -> {
                    if (outcome.error() == null) {
                        return CompletableFuture.completedFuture(
                                new SuccessfulAttempt(outcome.result(), text.toString()));
                    }
                    Throwable error = unwrap(outcome.error());
                    if (!(error instanceof AnthropicTransportException) || attempt >= 3) {
                        return CompletableFuture.failedFuture(error);
                    }
                    Duration delay = Duration.ofSeconds(attempt == 1 ? 1 : 2);
                    return retryDelay.waitFor(delay)
                            .thenCompose(ignored -> streamAttempt(request, events, attempt + 1));
                });
    }

    // 解开 completion wrapper 以保留 transport 错误类型
    private static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while ((current instanceof java.util.concurrent.CompletionException
                        || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    // 发布一个完整 LLM usage 事件
    private CompletionStage<Void> emitUsage(
            LlmRequest request, LlmUsage usage, LlmEventSink events) {
        return events.emit(new LlmUsageEvent(
                request.runId(),
                usage.inputTokens(),
                usage.outputTokens(),
                usage.cacheReadInputTokens(),
                usage.cacheCreationInputTokens(),
                usage.contextPercentage(),
                Instant.now(clock).toString()));
    }

    private record StreamOutcome(AnthropicStreamResult result, Throwable error) {}

    private record SuccessfulAttempt(AnthropicStreamResult result, String text) {}
}
