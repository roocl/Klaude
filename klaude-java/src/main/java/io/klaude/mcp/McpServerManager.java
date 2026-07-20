package io.klaude.mcp;

import io.klaude.tool.Tool;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

public final class McpServerManager implements AutoCloseable {
    private final McpConnector connector;
    private final CopyOnWriteArrayList<McpConnection> connections = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<Tool> tools = new CopyOnWriteArrayList<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    // 初始化 MCP connection factory
    public McpServerManager(McpConnector connector) {
        this.connector = java.util.Objects.requireNonNull(connector, "connector");
    }

    // 依次启动全部 server，并隔离单个 server 的连接或发现失败
    public CompletionStage<Void> startAll(List<McpServerSpec> servers) {
        CompletionStage<Void> completion = CompletableFuture.completedFuture(null);
        for (McpServerSpec server : List.copyOf(servers)) {
            completion = completion.thenCompose(ignored -> startOne(server));
        }
        return completion;
    }

    // 返回已发现 MCP tools 的不可变快照
    public List<Tool> tools() {
        return List.copyOf(tools);
    }

    // 连接一个 server 并缓存其 namespaced tools，失败时关闭并跳过
    private CompletionStage<Void> startOne(McpServerSpec server) {
        CompletableFuture<McpConnection> connected;
        try {
            connected = connector.connect(server).toCompletableFuture();
        } catch (Throwable error) {
            return CompletableFuture.completedFuture(null);
        }
        return connected.handle((connection, error) -> error == null ? connection : null)
                .thenCompose(connection -> {
                    if (connection == null) {
                        return CompletableFuture.completedFuture(null);
                    }
                    return connection.listTools()
                            .handle((definitions, error) -> {
                                if (error != null) {
                                    connection.close();
                                    return null;
                                }
                                List<Tool> discovered = new ArrayList<>();
                                definitions.forEach(definition -> discovered.add(
                                        new McpTool(connection, server.name(), definition)));
                                connections.add(connection);
                                tools.addAll(discovered);
                                return null;
                            });
                });
    }

    // 幂等关闭全部成功连接并清空工具快照
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        for (McpConnection connection : connections.reversed()) {
            try {
                connection.close();
            } catch (RuntimeException ignored) {
                // One broken server must not prevent remaining cleanup.
            }
        }
        connections.clear();
        tools.clear();
    }
}
