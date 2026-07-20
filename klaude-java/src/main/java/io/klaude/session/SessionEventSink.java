package io.klaude.session;

import io.klaude.protocol.Event;
import java.util.concurrent.CompletionStage;

@FunctionalInterface
public interface SessionEventSink {
    // 异步发布一个不可变 session 生命周期事件
    CompletionStage<Void> emit(Event event);
}
