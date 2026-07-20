package io.klaude.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.klaude.protocol.ProtocolJson;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

final class McpServerManagerTest {
    // 功能：验证单个 MCP server 启动失败被隔离且后续 server 工具仍可发现和关闭
    // 设计：connector 对 bad 返回 failed future、good 返回 fake connection，观察 tools 与 close
    @Test
    void isolatesFailedServerAndKeepsHealthyTools() throws Exception {
        var healthy = new FakeConnection();
        McpConnector connector = spec -> spec.name().equals("bad")
                ? CompletableFuture.failedFuture(new McpServerUnavailableException("offline"))
                : CompletableFuture.completedFuture(healthy);
        var manager = new McpServerManager(connector);
        var bad = McpServerSpec.tcp("bad", "127.0.0.1", 1);
        var good = McpServerSpec.stdio("good", List.of("fake"), Map.of());

        manager.startAll(List.of(bad, good)).toCompletableFuture().get();

        assertThat(manager.tools()).singleElement()
                .extracting(io.klaude.tool.Tool::name)
                .isEqualTo("good__echo");
        manager.close();
        assertThat(healthy.closed).isTrue();
    }

    private static final class FakeConnection implements McpConnection {
        private boolean closed;

        // 返回一个固定 echo definition
        @Override
        public java.util.concurrent.CompletionStage<List<McpToolDefinition>> listTools() {
            return CompletableFuture.completedFuture(List.of(new McpToolDefinition(
                    "echo", "Echo", ProtocolJson.mapper().createObjectNode().put("type", "object"))));
        }

        // 返回固定远端调用结果
        @Override
        public java.util.concurrent.CompletionStage<String> callTool(
                String name, com.fasterxml.jackson.databind.node.ObjectNode arguments) {
            return CompletableFuture.completedFuture("ok");
        }

        // 标记 fake connection 已关闭
        @Override
        public void close() {
            closed = true;
        }
    }
}
