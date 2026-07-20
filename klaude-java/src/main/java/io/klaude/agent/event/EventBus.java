package io.klaude.agent.event;

import io.klaude.protocol.Event;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;

public final class EventBus {
    private final CopyOnWriteArrayList<EventHandler> subscribers = new CopyOnWriteArrayList<>();

    // 按注册顺序添加一个 event handler
    public void subscribe(EventHandler handler) {
        subscribers.add(java.util.Objects.requireNonNull(handler, "handler"));
    }

    // 按注册顺序异步调用全部 subscriber 并返回整体 completion
    public CompletionStage<Void> publish(Event event) {
        CompletionStage<Void> completion = CompletableFuture.completedFuture(null);
        for (EventHandler subscriber : subscribers) {
            completion = completion.thenCompose(ignored -> subscriber.handle(event));
        }
        return completion;
    }
}
