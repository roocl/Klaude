package io.klaude.llm;

import java.util.concurrent.CompletionStage;

@FunctionalInterface
public interface LlmTokenSink {
    // 异步发布一个流式文本 token
    CompletionStage<Void> emit(String token);
}
