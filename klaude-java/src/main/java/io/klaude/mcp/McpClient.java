package io.klaude.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.klaude.protocol.ProtocolJson;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.ArrayList;
import java.util.List;

public final class McpClient implements McpConnection {
    private final McpLineTransport transport;
    private final Duration readTimeout;
    private final AtomicLong requestIds = new AtomicLong();
    private final AtomicBoolean closed = new AtomicBoolean();

    // 初始化一个尚未完成握手的 MCP client
    private McpClient(McpLineTransport transport, Duration readTimeout) {
        this.transport = java.util.Objects.requireNonNull(transport, "transport");
        this.readTimeout = java.util.Objects.requireNonNull(readTimeout, "readTimeout");
    }

    // 完成 initialize/initialized 握手后返回可用 client
    public static CompletionStage<McpClient> connect(
            McpLineTransport transport, Duration readTimeout) {
        var client = new McpClient(transport, readTimeout);
        ObjectNode params = ProtocolJson.mapper().createObjectNode();
        params.put("protocolVersion", "2024-11-05");
        params.set("capabilities", ProtocolJson.mapper().createObjectNode());
        params.set("clientInfo", ProtocolJson.mapper().createObjectNode()
                .put("name", "klaude-java")
                .put("version", "0.1"));
        return client.call("initialize", params)
                .thenCompose(ignored -> client.notifyInitialized())
                .thenApply(ignored -> client);
    }

    // 请求并解析 server 提供的全部 MCP 工具定义
    @Override
    public CompletionStage<List<McpToolDefinition>> listTools() {
        return call("tools/list", ProtocolJson.mapper().createObjectNode())
                .thenApply(result -> {
                    List<McpToolDefinition> tools = new ArrayList<>();
                    for (JsonNode item : result.path("tools")) {
                        ObjectNode schema = item.path("inputSchema") instanceof ObjectNode object
                                ? object
                                : ProtocolJson.mapper().createObjectNode()
                                        .put("type", "object")
                                        .set("properties", ProtocolJson.mapper().createObjectNode());
                        tools.add(new McpToolDefinition(
                                item.path("name").asText(""),
                                item.path("description").asText(""),
                                schema));
                    }
                    return List.copyOf(tools);
                });
    }

    // 调用一个 MCP 工具并按顺序拼接 text content blocks
    @Override
    public CompletionStage<String> callTool(String name, ObjectNode arguments) {
        ObjectNode params = ProtocolJson.mapper().createObjectNode();
        params.put("name", java.util.Objects.requireNonNull(name, "name"));
        params.set("arguments", java.util.Objects.requireNonNull(arguments, "arguments").deepCopy());
        return call("tools/call", params).thenApply(result -> {
            List<String> text = new ArrayList<>();
            for (JsonNode item : result.path("content")) {
                if (item.path("type").asText().equals("text") && item.has("text")) {
                    text.add(item.path("text").asText());
                }
            }
            return String.join("\n", text);
        });
    }

    // 发送一个 JSON-RPC 请求并等待匹配 ID 的 result
    private CompletionStage<JsonNode> call(String method, ObjectNode params) {
        long id = requestIds.incrementAndGet();
        ObjectNode request = ProtocolJson.mapper().createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", id);
        request.put("method", method);
        request.set("params", params.deepCopy());
        try {
            return transport.writeLine(ProtocolJson.mapper().writeValueAsString(request))
                    .thenCompose(ignored -> readMatching(Long.toString(id)));
        } catch (Exception error) {
            return CompletableFuture.failedFuture(new McpException(
                    "cannot encode MCP request", error));
        }
    }

    // 持续读取直到获得匹配 request ID 的响应
    private CompletionStage<JsonNode> readMatching(String expectedId) {
        return transport.readLine(readTimeout).thenCompose(line -> {
            final JsonNode response;
            try {
                response = ProtocolJson.mapper().readTree(line);
            } catch (Exception ignored) {
                return readMatching(expectedId);
            }
            if (!response.hasNonNull("id")
                    || !response.path("id").asText().equals(expectedId)) {
                return readMatching(expectedId);
            }
            if (response.has("error")) {
                JsonNode error = response.path("error");
                return CompletableFuture.failedFuture(new McpToolException(
                        error.path("code").asInt(),
                        error.path("message").asText("MCP request failed")));
            }
            return CompletableFuture.completedFuture(response.path("result").deepCopy());
        });
    }

    // 发送 notifications/initialized 无响应通知
    private CompletionStage<Void> notifyInitialized() {
        ObjectNode notification = ProtocolJson.mapper().createObjectNode();
        notification.put("jsonrpc", "2.0");
        notification.put("method", "notifications/initialized");
        notification.set("params", ProtocolJson.mapper().createObjectNode());
        try {
            return transport.writeLine(ProtocolJson.mapper().writeValueAsString(notification));
        } catch (Exception error) {
            return CompletableFuture.failedFuture(new McpException(
                    "cannot encode MCP notification", error));
        }
    }

    // 幂等关闭 MCP transport
    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            transport.close();
        }
    }
}
