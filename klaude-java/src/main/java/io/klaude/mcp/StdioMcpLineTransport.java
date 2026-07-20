package io.klaude.mcp;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

public final class StdioMcpLineTransport implements McpLineTransport {
    private final Process process;
    private final int maximumLineBytes;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final CompletableFuture<Void> stderrDrain;
    private final Object readLock = new Object();
    private final Object writeLock = new Object();
    private final AtomicBoolean closed = new AtomicBoolean();

    // 保存已启动 process 并立即开始 stderr drain
    private StdioMcpLineTransport(Process process, int maximumLineBytes) {
        this.process = process;
        this.maximumLineBytes = maximumLineBytes;
        this.stderrDrain = CompletableFuture.runAsync(() -> {
            try {
                process.getErrorStream().transferTo(OutputStream.nullOutputStream());
            } catch (IOException ignored) {
                // Closing the process streams ends the drainer.
            }
        }, executor);
    }

    // 启动一个继承当前环境并应用显式覆盖的 stdio MCP process
    public static StdioMcpLineTransport start(
            List<String> command, Map<String, String> environment, int maximumLineBytes)
            throws IOException {
        if (command.isEmpty()) {
            throw new IllegalArgumentException("command must not be empty");
        }
        if (maximumLineBytes < 1) {
            throw new IllegalArgumentException("maximumLineBytes must be positive");
        }
        ProcessBuilder builder = new ProcessBuilder(List.copyOf(command));
        builder.environment().putAll(Map.copyOf(environment));
        return new StdioMcpLineTransport(builder.start(), maximumLineBytes);
    }

    // 在虚拟线程中写出完整 UTF-8 stdin 行
    @Override
    public CompletionStage<Void> writeLine(String line) {
        return CompletableFuture.runAsync(() -> {
            synchronized (writeLock) {
                try {
                    process.getOutputStream().write(
                            (line + "\n").getBytes(StandardCharsets.UTF_8));
                    process.getOutputStream().flush();
                } catch (IOException error) {
                    throw new McpServerUnavailableException("MCP stdio write failed", error);
                }
            }
        }, executor);
    }

    // 在虚拟线程中读取有界 stdout 行并在 timeout 时终止 process
    @Override
    public CompletionStage<String> readLine(Duration timeout) {
        CompletableFuture<String> read = CompletableFuture.supplyAsync(() -> {
            synchronized (readLock) {
                try {
                    return McpLines.read(process.getInputStream(), maximumLineBytes);
                } catch (IOException error) {
                    throw new McpServerUnavailableException("MCP stdio read failed", error);
                }
            }
        }, executor);
        return read.orTimeout(Math.max(1, timeout.toMillis()), TimeUnit.MILLISECONDS)
                .exceptionallyCompose(error -> {
                    Throwable cause = unwrap(error);
                    if (cause instanceof TimeoutException) {
                        close();
                        return CompletableFuture.failedFuture(
                                new McpServerUnavailableException("MCP server read timeout"));
                    }
                    return CompletableFuture.failedFuture(cause);
                });
    }

    // 返回 process 当前是否仍存活
    public boolean isAlive() {
        return process.isAlive();
    }

    // 在指定时间内等待 process 退出
    public boolean awaitExit(Duration timeout) throws InterruptedException {
        return process.waitFor(Math.max(1, timeout.toMillis()), TimeUnit.MILLISECONDS);
    }

    // 幂等关闭 streams、process、stderr drainer 与 executor
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        try {
            process.getOutputStream().close();
        } catch (IOException ignored) {
            // Stream may already be closed after process exit.
        }
        process.destroy();
        try {
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
        } catch (InterruptedException error) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
        }
        try {
            process.getInputStream().close();
            process.getErrorStream().close();
        } catch (IOException ignored) {
            // Process teardown already released the pipe.
        }
        stderrDrain.cancel(true);
        executor.shutdownNow();
    }

    // 解开 completion wrappers 以保留 transport 错误分类
    private static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while ((current instanceof java.util.concurrent.CompletionException
                        || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }
}
