package io.klaude.mcp;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public final class DefaultMcpConnector implements McpConnector {
    public static final int DEFAULT_MAXIMUM_LINE_BYTES = 64 * 1024 * 1024;
    public static final Duration DEFAULT_READ_TIMEOUT = Duration.ofSeconds(30);
    private final int maximumLineBytes;
    private final Duration readTimeout;

    // 使用 64 MB 行限制与 30 秒读取超时创建生产 connector
    public DefaultMcpConnector() {
        this(DEFAULT_MAXIMUM_LINE_BYTES, DEFAULT_READ_TIMEOUT);
    }

    // 使用显式边界创建可测试 connector
    public DefaultMcpConnector(int maximumLineBytes, Duration readTimeout) {
        if (maximumLineBytes < 1) {
            throw new IllegalArgumentException("maximumLineBytes must be positive");
        }
        this.maximumLineBytes = maximumLineBytes;
        this.readTimeout = java.util.Objects.requireNonNull(readTimeout, "readTimeout");
    }

    // 根据 stdio 或 TCP 配置启动 transport 并完成 MCP 握手
    @Override
    public CompletionStage<McpConnection> connect(McpServerSpec spec) {
        java.util.Objects.requireNonNull(spec, "spec");
        final McpLineTransport transport;
        try {
            transport = switch (spec.transport()) {
                case "stdio" -> StdioMcpLineTransport.start(
                        spec.command(), spec.environment(), maximumLineBytes);
                case "tcp" -> TcpMcpLineTransport.connect(
                        spec.host(), spec.port(), maximumLineBytes);
                default -> throw new IllegalArgumentException(
                        "unknown MCP transport: " + spec.transport());
            };
        } catch (Throwable error) {
            return CompletableFuture.failedFuture(new McpServerUnavailableException(
                    "cannot start MCP server '" + spec.name() + "'", error));
        }
        return McpClient.connect(transport, readTimeout)
                .handle((client, error) -> {
                    if (error == null) {
                        return CompletableFuture.<McpConnection>completedFuture(client);
                    }
                    transport.close();
                    return CompletableFuture.<McpConnection>failedFuture(unwrap(error));
                })
                .thenCompose(stage -> stage);
    }

    // 解开 completion wrappers 以保留 MCP 错误分类
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
