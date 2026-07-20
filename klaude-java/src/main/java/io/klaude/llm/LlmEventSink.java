package io.klaude.llm;

import io.klaude.protocol.Event;
import java.util.concurrent.CompletionStage;

@FunctionalInterface
public interface LlmEventSink {
    // 异步发布一个 LLM 协议事件
    CompletionStage<Void> emit(Event event);
}
