package io.klaude.extension.subagent;

import io.klaude.agent.RunOutcome;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public final class BackgroundSubagentRegistry implements AutoCloseable {
    public enum Status {
        UNKNOWN,
        PENDING,
        COMPLETED,
        FAILED,
        CANCELLED
    }

    public record Poll(Status status, RunOutcome outcome, Throwable error) {
        // 校验 registry poll 状态
        public Poll {
            java.util.Objects.requireNonNull(status, "status");
        }
    }

    private record Entry(CompletableFuture<RunOutcome> result, CompletableFuture<RunOutcome> cancellation) {
        // 保存结果 future 与底层 runner cancellation future
    }

    private final ConcurrentHashMap<String, Entry> tasks =
            new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    // 注册一个可取消的后台 child completion
    public void register(String runId, CompletableFuture<RunOutcome> completion) {
        register(runId, completion, completion);
    }

    // 注册结果 future 与需要 shutdown 取消的底层 future
    public void register(
            String runId,
            CompletableFuture<RunOutcome> completion,
            CompletableFuture<RunOutcome> cancellation) {
        if (closed.get()) {
            completion.cancel(true);
            cancellation.cancel(true);
            throw new IllegalStateException("background subagent registry is closed");
        }
        if (tasks.putIfAbsent(runId, new Entry(completion, cancellation)) != null) {
            throw new IllegalArgumentException("duplicate subagent run ID: " + runId);
        }
    }

    // 查询状态并仅在终态时原子消费一次结果
    public Poll poll(String runId) {
        Entry entry = tasks.get(runId);
        if (entry == null) {
            return new Poll(Status.UNKNOWN, null, null);
        }
        CompletableFuture<RunOutcome> completion = entry.result();
        if (!completion.isDone()) {
            return new Poll(Status.PENDING, null, null);
        }
        if (!tasks.remove(runId, entry)) {
            return new Poll(Status.UNKNOWN, null, null);
        }
        if (completion.isCancelled()) {
            return new Poll(Status.CANCELLED, null, null);
        }
        try {
            return new Poll(Status.COMPLETED, completion.join(), null);
        } catch (java.util.concurrent.CompletionException error) {
            return new Poll(Status.FAILED, null, error.getCause());
        }
    }

    // 返回当前未消费后台 child 数量
    public int activeCount() {
        return tasks.size();
    }

    // 取消并清空全部后台 child completions
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        tasks.values().forEach(entry -> {
            entry.result().cancel(true);
            entry.cancellation().cancel(true);
        });
        tasks.clear();
    }
}
