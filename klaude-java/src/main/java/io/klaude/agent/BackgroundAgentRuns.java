package io.klaude.agent;

import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

public final class BackgroundAgentRuns implements Function<String, String>, AutoCloseable {
    private final Supplier<String> runIds;
    private final BiFunction<String, String, CompletionStage<RunOutcome>> execution;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final ConcurrentHashMap<String, Future<?>> active = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    // 初始化 run ID 来源和异步执行边界
    public BackgroundAgentRuns(
            Supplier<String> runIds,
            BiFunction<String, String, CompletionStage<RunOutcome>> execution) {
        this.runIds = java.util.Objects.requireNonNull(runIds, "runIds");
        this.execution = java.util.Objects.requireNonNull(execution, "execution");
    }

    // 后台启动 run 并立即返回新 ID
    @Override
    public String apply(String goal) {
        if (closed.get()) {
            throw new IllegalStateException("background runs are closed");
        }
        String runId = java.util.Objects.requireNonNull(runIds.get(), "run ID");
        var start = new CountDownLatch(1);
        Future<?> task = executor.submit(() -> execute(runId, goal, start));
        active.put(runId, task);
        start.countDown();
        return runId;
    }

    // 在注册完成后执行 run 并清理 active entry
    private void execute(String runId, String goal, CountDownLatch start) {
        java.util.concurrent.CompletableFuture<RunOutcome> completion = null;
        try {
            start.await();
            completion = execution.apply(runId, goal).toCompletableFuture();
            completion.get();
        } catch (InterruptedException error) {
            if (completion != null) {
                completion.cancel(true);
            }
            Thread.currentThread().interrupt();
        } catch (java.util.concurrent.ExecutionException
                 | java.util.concurrent.CancellationException ignored) {
            // Runner publishes the observable failure lifecycle.
        } finally {
            active.remove(runId);
        }
    }

    // 返回当前活跃 run 数量
    public int activeCount() {
        return active.size();
    }

    // 取消全部活跃 run 并关闭 virtual-thread executor
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        active.values().forEach(task -> task.cancel(true));
        executor.shutdownNow();
        try {
            executor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
        active.clear();
    }
}
