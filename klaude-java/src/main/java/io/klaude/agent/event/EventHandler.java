package io.klaude.agent.event;

import io.klaude.protocol.Event;
import java.util.concurrent.CompletionStage;

@FunctionalInterface
public interface EventHandler {
    // 异步处理一个不可变协议事件
    CompletionStage<Void> handle(Event event);
}
