package io.klaude.llm;

import java.util.concurrent.CompletionStage;

@FunctionalInterface
public interface LlmProvider {
    // 根据不可变请求生成一个异步 LLM 响应
    CompletionStage<LlmResponse> chat(LlmRequest request, LlmEventSink events);
}
