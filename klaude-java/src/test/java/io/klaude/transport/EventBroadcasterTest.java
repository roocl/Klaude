package io.klaude.transport;

import static org.assertj.core.api.Assertions.assertThat;

import io.klaude.protocol.EventSubscribeCommand;
import io.klaude.protocol.EventSubscribeResult;
import io.klaude.protocol.Command;
import io.klaude.protocol.ProtocolJson;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

final class EventBroadcasterTest {
    // 功能：验证订阅响应后匹配事件以完整 push envelope 写到同一 JSON-RPC 连接
    // 设计：真实 socket 先 subscribe 再调用测试 publish handler，允许 push 与 publish response 交错
    @Test
    void subscribesAndPushesMatchingEventsOverRealSocket() throws Exception {
        var mapper = ProtocolJson.mapper();
        EventBroadcaster broadcaster = new EventBroadcaster(() -> "12345678");
        RpcDispatcher dispatcher = new RpcDispatcher();
        dispatcher.register("event.subscribe", (connection, params) -> {
            try {
                var commandParams = params.deepCopy();
                commandParams.put("type", "event.subscribe");
                EventSubscribeCommand command = (EventSubscribeCommand) mapper.treeToValue(
                        commandParams, Command.class);
                String subscriptionId = broadcaster.subscribe(
                        connection, command.topics(), command.scope());
                return CompletableFuture.completedFuture(mapper.valueToTree(
                        new EventSubscribeResult(subscriptionId, 0)));
            } catch (Exception error) {
                throw new InvalidParamsException("invalid subscription", error);
            }
        });
        dispatcher.register("test.publish", (connection, params) -> {
            var event = mapper.createObjectNode()
                    .put("type", "run.started")
                    .put("run_id", "run-001")
                    .put("goal", "test")
                    .put("ts", "2026-07-19T10:15:30Z");
            return broadcaster.publish(event).thenApply(count -> mapper.createObjectNode().put("sent", count));
        });
        try (NdjsonServer server = new NdjsonServer(
                "127.0.0.1", 0, 64 * 1024 * 1024, dispatcher)) {
            server.start().get(5, java.util.concurrent.TimeUnit.SECONDS);
            try (Socket socket = new Socket("127.0.0.1", server.port());
                 var writer = new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8);
                 var reader = new BufferedReader(new InputStreamReader(
                         socket.getInputStream(), StandardCharsets.UTF_8))) {
                socket.setSoTimeout(5_000);
                writer.write("{\"jsonrpc\":\"2.0\",\"id\":\"subscribe\","
                        + "\"method\":\"event.subscribe\","
                        + "\"params\":{\"topics\":[\"run.*\"],\"scope\":\"run:run-001\"}}\n");
                writer.flush();
                var subscribeResponse = mapper.readTree(reader.readLine());
                assertThat(subscribeResponse.path("result").path("subscription_id").asText())
                        .isEqualTo("sub-12345678");

                writer.write("{\"jsonrpc\":\"2.0\",\"id\":\"publish\","
                        + "\"method\":\"test.publish\",\"params\":{}}\n");
                writer.flush();
                var first = mapper.readTree(reader.readLine());
                var second = mapper.readTree(reader.readLine());
                var push = first.path("kind").asText().equals("event") ? first : second;
                var response = first.has("jsonrpc") ? first : second;

                assertThat(push.path("event").path("type").asText()).isEqualTo("run.started");
                assertThat(response.path("id").asText()).isEqualTo("publish");
                assertThat(response.path("result").path("sent").asInt()).isEqualTo(1);
            }
        }
    }

    // 功能：验证同一连接重复订阅会分别存在，并在客户端断开后全部立即清理
    // 设计：通过真实 handler 连续订阅两次，观察 count=2，关闭 Socket 后轮询公开 count 到零
    @Test
    void removesDuplicateSubscriptionsWhenClientDisconnects() throws Exception {
        java.util.concurrent.atomic.AtomicInteger ids = new java.util.concurrent.atomic.AtomicInteger();
        EventBroadcaster broadcaster = new EventBroadcaster(
                () -> "%08d".formatted(ids.incrementAndGet()));
        RpcDispatcher dispatcher = new RpcDispatcher();
        dispatcher.register("event.subscribe", (connection, params) -> CompletableFuture.completedFuture(
                ProtocolJson.mapper().createObjectNode().put(
                        "subscription_id",
                        broadcaster.subscribe(connection, java.util.List.of("run.*"), "global"))));
        try (NdjsonServer server = new NdjsonServer(
                "127.0.0.1", 0, 64 * 1024 * 1024, dispatcher)) {
            server.start().get(5, java.util.concurrent.TimeUnit.SECONDS);
            try (Socket socket = new Socket("127.0.0.1", server.port());
                 var writer = new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8);
                 var reader = new BufferedReader(new InputStreamReader(
                         socket.getInputStream(), StandardCharsets.UTF_8))) {
                socket.setSoTimeout(5_000);
                writer.write("{\"jsonrpc\":\"2.0\",\"id\":\"one\","
                        + "\"method\":\"event.subscribe\",\"params\":{}}\n");
                writer.write("{\"jsonrpc\":\"2.0\",\"id\":\"two\","
                        + "\"method\":\"event.subscribe\",\"params\":{}}\n");
                writer.flush();
                reader.readLine();
                reader.readLine();
                assertThat(broadcaster.subscriptionCount()).isEqualTo(2);
            }
            org.assertj.core.api.Assertions.assertThatNoException().isThrownBy(() -> {
                for (int attempt = 0; attempt < 50 && broadcaster.subscriptionCount() != 0; attempt++) {
                    Thread.sleep(20);
                }
            });
            assertThat(broadcaster.subscriptionCount()).isZero();
        }
    }
}
