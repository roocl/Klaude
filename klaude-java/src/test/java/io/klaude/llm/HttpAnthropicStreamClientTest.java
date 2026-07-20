package io.klaude.llm;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import io.klaude.protocol.ProtocolJson;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class HttpAnthropicStreamClientTest {
    // 功能：验证 HTTP SSE client 解析 text、thinking、tool call、stop reason 和 usage
    // 设计：临时 HTTP server 返回完整 SSE 序列，同时捕获请求 JSON 并观察结果
    @Test
    void sendsRequestAndParsesCompleteSseStream() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        String sse = """
                event: message_start
                data: {"type":"message_start","message":{"usage":{"input_tokens":100,"cache_read_input_tokens":10,"cache_creation_input_tokens":5}}}

                event: content_block_start
                data: {"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":""}}

                event: content_block_delta
                data: {"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"plan"}}

                event: content_block_delta
                data: {"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"sig"}}

                event: content_block_start
                data: {"type":"content_block_start","index":1,"content_block":{"type":"text","text":""}}

                event: content_block_delta
                data: {"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"hello"}}

                event: content_block_start
                data: {"type":"content_block_start","index":2,"content_block":{"type":"tool_use","id":"tool-001","name":"read_file","input":{}}}

                event: content_block_delta
                data: {"type":"content_block_delta","index":2,"delta":{"type":"input_json_delta","partial_json":"{\\\"path\\\":\\\"README.md\\\"}"}}

                event: message_delta
                data: {"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":20}}

                event: message_stop
                data: {"type":"message_stop"}

                """;
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/messages", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = sse.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        var tokens = new CopyOnWriteArrayList<String>();
        try (var client = new HttpAnthropicStreamClient(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()), "test-key")) {
            var message = ProtocolJson.mapper().createObjectNode()
                    .put("role", "user")
                    .put("content", "read");
            com.fasterxml.jackson.databind.node.ObjectNode tool = ProtocolJson.mapper().createObjectNode()
                    .put("name", "read_file")
                    .put("description", "read");
            tool.set("input_schema", ProtocolJson.mapper().createObjectNode().put("type", "object"));
            var request = new AnthropicStreamRequest(
                    "test-model",
                    new LlmRequest("run-001", 1, List.of(message), List.of(tool), "system"),
                    8192);

            AnthropicStreamResult result = client.stream(request, token -> {
                        tokens.add(token);
                        return CompletableFuture.completedFuture(null);
                    })
                    .toCompletableFuture().get();

            assertThat(tokens).containsExactly("hello");
            assertThat(result.stopReason()).isEqualTo(LlmStopReason.TOOL_USE);
            assertThat(result.thinkingBlocks()).singleElement().satisfies(block -> {
                assertThat(block.path("thinking").asText()).isEqualTo("plan");
                assertThat(block.path("signature").asText()).isEqualTo("sig");
            });
            assertThat(result.toolCalls()).singleElement().satisfies(call -> {
                assertThat(call.id()).isEqualTo("tool-001");
                assertThat(call.name()).isEqualTo("read_file");
                assertThat(call.input().path("path").asText()).isEqualTo("README.md");
            });
            assertThat(result.usage()).isEqualTo(new LlmUsage(100, 20, 10, 5, 0.0005));
            assertThat(ProtocolJson.mapper().readTree(requestBody.get()).path("stream").asBoolean())
                    .isTrue();
        } finally {
            server.stop(0);
        }
    }
}
