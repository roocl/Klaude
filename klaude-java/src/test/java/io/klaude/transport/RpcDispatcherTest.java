package io.klaude.transport;

import static org.assertj.core.api.Assertions.assertThat;

import io.klaude.protocol.ProtocolJson;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class RpcDispatcherTest {
    // 功能：验证 malformed、invalid request 和 unknown method 返回冻结的 JSON-RPC 错误码
    // 设计：在同一真实连接连续发送三条坏请求，证明单条错误不会终止后续 request dispatch
    @Test
    void returnsParseInvalidRequestAndMethodNotFoundErrors() throws Exception {
        RpcDispatcher dispatcher = new RpcDispatcher();
        try (NdjsonServer server = new NdjsonServer(
                "127.0.0.1", 0, 64 * 1024 * 1024, dispatcher)) {
            server.start().get(5, java.util.concurrent.TimeUnit.SECONDS);
            try (Socket socket = new Socket("127.0.0.1", server.port());
                 var writer = new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8);
                 var reader = new BufferedReader(new InputStreamReader(
                         socket.getInputStream(), StandardCharsets.UTF_8))) {
                socket.setSoTimeout(5_000);
                writer.write("{not-json\n");
                writer.write("{\"jsonrpc\":\"2.0\",\"method\":\"core.ping\"}\n");
                writer.write("{\"jsonrpc\":\"2.0\",\"id\":\"missing-1\","
                        + "\"method\":\"core.missing\",\"params\":{}}\n");
                writer.flush();

                var mapper = ProtocolJson.mapper();
                var errors = java.util.List.of(
                        mapper.readTree(reader.readLine()),
                        mapper.readTree(reader.readLine()),
                        mapper.readTree(reader.readLine()));

                assertThat(errors).extracting(node -> node.path("error").path("code").asInt())
                        .containsExactlyInAnyOrder(-32700, -32600, -32601);
                assertThat(errors.stream()
                        .filter(node -> node.path("error").path("code").asInt() == -32601)
                        .findFirst()
                        .orElseThrow()
                        .path("id").asText()).isEqualTo("missing-1");
            }
        }
    }

    // 功能：验证注册 handler 的 success、invalid params、internal error 分类且不运行在 event loop
    // 设计：真实连接发送三条请求，由两个 handler 分别返回 pong、参数异常和意外异常，观察 wire envelope
    @Test
    void dispatchesRegisteredHandlersAndClassifiesFailures() throws Exception {
        var mapper = ProtocolJson.mapper();
        AtomicReference<String> handlerThread = new AtomicReference<>();
        RpcDispatcher dispatcher = new RpcDispatcher();
        dispatcher.register("core.ping", (connection, params) -> {
            handlerThread.set(Thread.currentThread().getName());
            if (!params.path("client").isTextual()) {
                throw new InvalidParamsException("client must be a string");
            }
            return CompletableFuture.completedFuture(mapper.createObjectNode()
                    .put("server_version", "0.1.0")
                    .put("uptime_ms", 10)
                    .put("received_at", "2026-07-19T10:15:30Z"));
        });
        dispatcher.register("test.fail", (connection, params) -> {
            throw new IllegalStateException("boom");
        });
        try (NdjsonServer server = new NdjsonServer(
                "127.0.0.1", 0, 64 * 1024 * 1024, dispatcher)) {
            server.start().get(5, java.util.concurrent.TimeUnit.SECONDS);
            try (Socket socket = new Socket("127.0.0.1", server.port());
                 var writer = new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8);
                 var reader = new BufferedReader(new InputStreamReader(
                         socket.getInputStream(), StandardCharsets.UTF_8))) {
                socket.setSoTimeout(5_000);
                writer.write("{\"jsonrpc\":\"2.0\",\"id\":\"ok\",\"method\":\"core.ping\","
                        + "\"params\":{\"client\":\"test\"}}\n");
                writer.write("{\"jsonrpc\":\"2.0\",\"id\":\"bad\",\"method\":\"core.ping\","
                        + "\"params\":{\"client\":42}}\n");
                writer.write("{\"jsonrpc\":\"2.0\",\"id\":\"fail\",\"method\":\"test.fail\","
                        + "\"params\":{}}\n");
                writer.flush();

                var responses = java.util.List.of(
                        mapper.readTree(reader.readLine()),
                        mapper.readTree(reader.readLine()),
                        mapper.readTree(reader.readLine()));

                assertThat(responses.stream()
                        .filter(node -> node.path("id").asText().equals("ok"))
                        .findFirst().orElseThrow().path("result").path("server_version").asText())
                        .isEqualTo("0.1.0");
                assertThat(responses.stream()
                        .filter(node -> node.path("id").asText().equals("bad"))
                        .findFirst().orElseThrow().path("error").path("code").asInt())
                        .isEqualTo(-32600);
                assertThat(responses.stream()
                        .filter(node -> node.path("id").asText().equals("fail"))
                        .findFirst().orElseThrow().path("error").path("code").asInt())
                        .isEqualTo(-32603);
                assertThat(handlerThread.get()).doesNotContain("nioEventLoop");
            }
        }
    }

    // 功能：验证超过 NDJSON 行长限制的请求返回 Request too large 后关闭连接
    // 设计：把测试 server 限制缩小到 64 bytes，发送超长完整行并观察错误 code/message
    @Test
    void rejectsOversizedRequestBeforeClosingConnection() throws Exception {
        var mapper = ProtocolJson.mapper();
        try (NdjsonServer server = new NdjsonServer(
                "127.0.0.1", 0, 64, new RpcDispatcher())) {
            server.start().get(5, java.util.concurrent.TimeUnit.SECONDS);
            try (Socket socket = new Socket("127.0.0.1", server.port());
                 var writer = new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8);
                 var reader = new BufferedReader(new InputStreamReader(
                         socket.getInputStream(), StandardCharsets.UTF_8))) {
                socket.setSoTimeout(5_000);
                writer.write("x".repeat(100) + "\n");
                writer.flush();

                var response = mapper.readTree(reader.readLine());

                assertThat(response.path("error").path("code").asInt()).isEqualTo(-32600);
                assertThat(response.path("error").path("message").asText())
                        .isEqualTo("Request too large");
                assertThat(reader.readLine()).isNull();
            }
        }
    }

    // 功能：验证同一连接的并发 request ID 独立完成且慢 handler 不阻塞快响应
    // 设计：先发 200ms slow 再发 immediate fast，断言 fast 行先到且每个 result 仍对应原 ID
    @Test
    void completesConcurrentRequestIdsIndependently() throws Exception {
        var mapper = ProtocolJson.mapper();
        RpcDispatcher dispatcher = new RpcDispatcher();
        dispatcher.register("test.delay", (connection, params) -> CompletableFuture.supplyAsync(
                () -> mapper.createObjectNode().put("value", params.path("value").asText()),
                CompletableFuture.delayedExecutor(
                        params.path("delay_ms").asLong(),
                        java.util.concurrent.TimeUnit.MILLISECONDS)));
        try (NdjsonServer server = new NdjsonServer(
                "127.0.0.1", 0, 64 * 1024 * 1024, dispatcher)) {
            server.start().get(5, java.util.concurrent.TimeUnit.SECONDS);
            try (Socket socket = new Socket("127.0.0.1", server.port());
                 var writer = new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8);
                 var reader = new BufferedReader(new InputStreamReader(
                         socket.getInputStream(), StandardCharsets.UTF_8))) {
                socket.setSoTimeout(5_000);
                writer.write("{\"jsonrpc\":\"2.0\",\"id\":\"slow\",\"method\":\"test.delay\","
                        + "\"params\":{\"delay_ms\":200,\"value\":\"slow-value\"}}\n");
                writer.write("{\"jsonrpc\":\"2.0\",\"id\":\"fast\",\"method\":\"test.delay\","
                        + "\"params\":{\"delay_ms\":0,\"value\":\"fast-value\"}}\n");
                writer.flush();

                var fast = mapper.readTree(reader.readLine());
                var slow = mapper.readTree(reader.readLine());

                assertThat(fast.path("id").asText()).isEqualTo("fast");
                assertThat(fast.path("result").path("value").asText()).isEqualTo("fast-value");
                assertThat(slow.path("id").asText()).isEqualTo("slow");
                assertThat(slow.path("result").path("value").asText()).isEqualTo("slow-value");
            }
        }
    }
}
