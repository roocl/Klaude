package io.klaude.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.klaude.protocol.RunFinishedEvent;
import org.junit.jupiter.api.Test;

final class RunWaiterTest {
    // 功能：支持结束事件早于 RPC run ID 响应到达
    // 设计：先缓存结束事件，再注册等待者验证竞态不会丢信号
    @Test
    void remembersEarlyFinishedEvent() {
        var waiter = new RunWaiter();
        var event = new RunFinishedEvent("run-1", "success", "", 2, "2026-07-20T00:00:00Z");

        waiter.finished(event);

        assertThat(waiter.await("run-1").join()).isEqualTo(event);
    }
}
