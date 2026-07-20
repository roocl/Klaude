package io.klaude.mcp;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class TcpMcpLineTransport implements McpLineTransport {
    private final Socket socket;
    private final int maximumLineBytes;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Object readLock = new Object();
    private final Object writeLock = new Object();
    private final AtomicBoolean closed = new AtomicBoolean();

    // 保存已连接 socket 与行限制
    private TcpMcpLineTransport(Socket socket, int maximumLineBytes) {
        this.socket = socket;
        this.maximumLineBytes = maximumLineBytes;
    }

    // 连接一个 TCP MCP server
    public static TcpMcpLineTransport connect(
            String host, int port, int maximumLineBytes) throws IOException {
        if (maximumLineBytes < 1) {
            throw new IllegalArgumentException("maximumLineBytes must be positive");
        }
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress(host, port), 30_000);
        return new TcpMcpLineTransport(socket, maximumLineBytes);
    }

    // 在虚拟线程中写出完整 UTF-8 行
    @Override
    public CompletionStage<Void> writeLine(String line) {
        return CompletableFuture.runAsync(() -> {
            synchronized (writeLock) {
                try {
                    socket.getOutputStream().write(
                            (line + "\n").getBytes(StandardCharsets.UTF_8));
                    socket.getOutputStream().flush();
                } catch (IOException error) {
                    throw new McpServerUnavailableException("MCP TCP write failed", error);
                }
            }
        }, executor);
    }

    // 在虚拟线程中按 socket timeout 读取一条有界响应
    @Override
    public CompletionStage<String> readLine(Duration timeout) {
        return CompletableFuture.supplyAsync(() -> {
            synchronized (readLock) {
                try {
                    socket.setSoTimeout(Math.toIntExact(Math.max(1, timeout.toMillis())));
                    return McpLines.read(socket.getInputStream(), maximumLineBytes);
                } catch (SocketTimeoutException error) {
                    throw new McpServerUnavailableException("MCP server read timeout", error);
                } catch (IOException error) {
                    throw new McpServerUnavailableException("MCP TCP read failed", error);
                }
            }
        }, executor);
    }

    // 幂等关闭 socket 与 I/O executor
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        try {
            socket.close();
        } catch (IOException ignored) {
            // Socket is already unusable.
        }
        executor.shutdownNow();
    }
}
