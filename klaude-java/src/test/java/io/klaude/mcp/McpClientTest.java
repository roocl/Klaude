package io.klaude.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

final class McpClientTest {
    // 功能：验证 MCP client 在可用前完成 initialize 请求和 initialized 通知握手
    // 设计：内存 line transport 预置 initialize 响应，通过公开 connect 观察精确写出方法顺序
    @Test
    void initializesBeforeConnectionBecomesReady() throws Exception {
        var transport = new FakeLineTransport(List.of(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"protocolVersion\":\"2024-11-05\"}}"));

        McpClient client = McpClient.connect(transport, Duration.ofSeconds(30))
                .toCompletableFuture().get();

        assertThat(client).isNotNull();
        assertThat(transport.writes).hasSize(2);
        assertThat(io.klaude.protocol.ProtocolJson.mapper().readTree(transport.writes.get(0))
                .path("method").asText()).isEqualTo("initialize");
        var initialized = io.klaude.protocol.ProtocolJson.mapper()
                .readTree(transport.writes.get(1));
        assertThat(initialized.path("method").asText())
                .isEqualTo("notifications/initialized");
        assertThat(initialized.has("id")).isFalse();
        client.close();
        assertThat(transport.closed).isTrue();
    }

    // 功能：验证 tools/list 忽略 server notification 并接受字符串响应 ID
    // 设计：握手后预置无 ID 通知和 id="2" 的工具响应，通过公开 listTools 观察定义
    @Test
    void listsToolsAfterNotificationWithStringResponseId() throws Exception {
        var transport = new FakeLineTransport(List.of(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}",
                "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/progress\",\"params\":{}}",
                "{\"jsonrpc\":\"2.0\",\"id\":\"2\",\"result\":{\"tools\":[{"
                        + "\"name\":\"echo\",\"description\":\"Echo text\","
                        + "\"inputSchema\":{\"type\":\"object\",\"properties\":{"
                        + "\"text\":{\"type\":\"string\"}}}}]}}"));
        McpClient client = McpClient.connect(transport, Duration.ofSeconds(30))
                .toCompletableFuture().get();

        List<McpToolDefinition> tools = client.listTools().toCompletableFuture().get();

        assertThat(tools).singleElement().satisfies(tool -> {
            assertThat(tool.name()).isEqualTo("echo");
            assertThat(tool.description()).isEqualTo("Echo text");
            assertThat(tool.inputSchema().path("properties").has("text")).isTrue();
        });
        assertThat(io.klaude.protocol.ProtocolJson.mapper()
                .readTree(transport.writes.get(2)).path("method").asText())
                .isEqualTo("tools/list");
        client.close();
    }

    // 功能：验证 tools/call 按顺序拼接全部 text blocks 并忽略其他内容类型
    // 设计：握手后预置混合 content 响应，通过公开 callTool 观察文本及写出 arguments
    @Test
    void callsToolAndJoinsTextContent() throws Exception {
        var transport = new FakeLineTransport(List.of(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}",
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"content\":["
                        + "{\"type\":\"text\",\"text\":\"first\"},"
                        + "{\"type\":\"image\",\"data\":\"ignored\"},"
                        + "{\"type\":\"text\",\"text\":\"second\"}]}}"));
        McpClient client = McpClient.connect(transport, Duration.ofSeconds(30))
                .toCompletableFuture().get();
        var arguments = io.klaude.protocol.ProtocolJson.mapper().createObjectNode()
                .put("text", "hello");

        String result = client.callTool("echo", arguments).toCompletableFuture().get();

        assertThat(result).isEqualTo("first\nsecond");
        var request = io.klaude.protocol.ProtocolJson.mapper().readTree(transport.writes.get(2));
        assertThat(request.path("method").asText()).isEqualTo("tools/call");
        assertThat(request.path("params").path("name").asText()).isEqualTo("echo");
        assertThat(request.path("params").path("arguments").path("text").asText())
                .isEqualTo("hello");
        client.close();
    }

    // 功能：验证 JSON-RPC application error 保留 code 并分类为 McpToolException
    // 设计：握手后 tools/call 返回 error object，通过公开 future 观察根异常字段
    @Test
    void classifiesApplicationErrorFromToolCall() throws Exception {
        var transport = new FakeLineTransport(List.of(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}",
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"error\":{"
                        + "\"code\":-32001,\"message\":\"tool failed\"}}"));
        McpClient client = McpClient.connect(transport, Duration.ofSeconds(30))
                .toCompletableFuture().get();

        var completion = client.callTool(
                        "broken", io.klaude.protocol.ProtocolJson.mapper().createObjectNode())
                .toCompletableFuture();

        assertThatThrownBy(completion::join)
                .hasRootCauseInstanceOf(McpToolException.class)
                .rootCause()
                .satisfies(error -> {
                    assertThat(error.getMessage()).isEqualTo("tool failed (code=-32001)");
                    assertThat(((McpToolException) error).code()).isEqualTo(-32001);
                });
        client.close();
    }

    private static final class FakeLineTransport implements McpLineTransport {
        private final ArrayDeque<String> reads;
        private final CopyOnWriteArrayList<String> writes = new CopyOnWriteArrayList<>();
        private boolean closed;

        // 初始化内存响应队列
        private FakeLineTransport(List<String> reads) {
            this.reads = new ArrayDeque<>(reads);
        }

        // 记录一条 client 写出行
        @Override
        public java.util.concurrent.CompletionStage<Void> writeLine(String line) {
            writes.add(line);
            return CompletableFuture.completedFuture(null);
        }

        // 返回下一条预置 server 响应
        @Override
        public java.util.concurrent.CompletionStage<String> readLine(Duration timeout) {
            return CompletableFuture.completedFuture(reads.removeFirst());
        }

        // 标记 transport 已关闭
        @Override
        public void close() {
            closed = true;
        }
    }
}
