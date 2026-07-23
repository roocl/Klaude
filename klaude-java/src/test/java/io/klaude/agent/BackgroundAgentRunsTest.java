package io.klaude.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

final class BackgroundAgentRunsTest {
    // 功能：验证 background launcher 立即返回 ID 并在 close 时取消活跃 run
    // 设计：执行返回永久 pending future，等待 worker 取得它后关闭并观察取消传播
    @Test
    void cancelsActiveRunsOnClose() throws Exception {
        var pending = new CompletableFuture<RunOutcome>();
        var executionStarted = new CountDownLatch(1);
        var runs = new BackgroundAgentRuns(
                () -> "run-fixed",
                (runId, goal) -> {
                    executionStarted.countDown();
                    return pending;
                });

        assertThat(runs.apply("goal")).isEqualTo("run-fixed");
        assertThat(executionStarted.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(runs.activeCount()).isEqualTo(1);

        runs.close();

        assertThat(pending).isCancelled();
        assertThat(runs.activeCount()).isZero();
    }

    // 功能：按 run ID 取消单个活跃任务
    // 设计：启动永久 pending execution，调用 cancel 后验证 future 与 registry 状态
    @Test
    void cancelsOneActiveRunById() throws Exception {
        var pending = new CompletableFuture<RunOutcome>();
        var executionStarted = new CountDownLatch(1);
        var cancelledRun = new java.util.concurrent.atomic.AtomicReference<String>();
        try (var runs = new BackgroundAgentRuns(
                () -> "run-fixed",
                (runId, goal) -> {
                    executionStarted.countDown();
                    return pending;
                },
                cancelledRun::set)) {
            runs.apply("goal");
            assertThat(executionStarted.await(1, TimeUnit.SECONDS)).isTrue();

            assertThat(runs.cancel("run-fixed")).isTrue();
            for (int attempt = 0; attempt < 50 && !pending.isCancelled(); attempt++) {
                Thread.sleep(10);
            }
            assertThat(pending).isCancelled();
            assertThat(cancelledRun.get()).isEqualTo("run-fixed");
            assertThat(runs.cancel("missing")).isFalse();
        }
    }
}
