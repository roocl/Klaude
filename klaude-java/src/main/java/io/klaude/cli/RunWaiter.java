package io.klaude.cli;

import io.klaude.protocol.RunFinishedEvent;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

final class RunWaiter {
    private final ConcurrentHashMap<String, CompletableFuture<RunFinishedEvent>> waiting =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, RunFinishedEvent> completed = new ConcurrentHashMap<>();

    // 记录 run 完成并唤醒已注册等待者
    void finished(RunFinishedEvent event) {
        CompletableFuture<RunFinishedEvent> future = waiting.remove(event.runId());
        if (future == null) {
            completed.put(event.runId(), event);
        } else {
            future.complete(event);
        }
    }

    // 等待指定 run 的结束事件并兼容先到事件
    CompletableFuture<RunFinishedEvent> await(String runId) {
        RunFinishedEvent event = completed.remove(runId);
        if (event != null) {
            return CompletableFuture.completedFuture(event);
        }
        CompletableFuture<RunFinishedEvent> future = new CompletableFuture<>();
        CompletableFuture<RunFinishedEvent> previous = waiting.putIfAbsent(runId, future);
        return previous == null ? future : previous;
    }
}
