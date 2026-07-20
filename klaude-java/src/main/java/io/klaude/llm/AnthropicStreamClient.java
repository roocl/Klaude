package io.klaude.llm;

import java.util.concurrent.CompletionStage;

@FunctionalInterface
public interface AnthropicStreamClient {
    // 流式执行一个 Anthropic 请求并返回最终结果
    CompletionStage<AnthropicStreamResult> stream(
            AnthropicStreamRequest request, LlmTokenSink tokens);
}
