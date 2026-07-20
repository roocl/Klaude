package io.klaude.agent.event;

import static org.assertj.core.api.Assertions.assertThat;

import io.klaude.protocol.CoreStartedEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

final class EventBusTest {
    // 功能：验证 event 按 subscriber 注册顺序异步发布且完成 future 覆盖全部 handler
    // 设计：两个 subscriber 记录不同标记，等待公开 publish completion 后比较确定顺序
    @Test
    void publishesToSubscribersInRegistrationOrder() throws Exception {
        EventBus bus = new EventBus();
        List<String> calls = new ArrayList<>();
        bus.subscribe(event -> {
            calls.add("first:" + ((CoreStartedEvent) event).version());
            return CompletableFuture.completedFuture(null);
        });
        bus.subscribe(event -> {
            calls.add("second:" + ((CoreStartedEvent) event).listenAddr());
            return CompletableFuture.completedFuture(null);
        });

        bus.publish(new CoreStartedEvent("127.0.0.1:7437", "0.1.0"))
                .toCompletableFuture().get();

        assertThat(calls).containsExactly("first:0.1.0", "second:127.0.0.1:7437");
    }
}
