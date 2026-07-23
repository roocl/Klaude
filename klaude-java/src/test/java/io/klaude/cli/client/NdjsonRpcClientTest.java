package io.klaude.cli.client;

import static org.assertj.core.api.Assertions.assertThat;

import io.klaude.protocol.Event;
import io.klaude.protocol.PingCommand;
import io.klaude.protocol.PongResult;
import io.klaude.protocol.RunStartedEvent;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

final class NdjsonRpcClientTest {
    // 功能：在同一连接上分流事件推送与 RPC 响应
    // 设计：fake daemon 先推事件再回复 ping，验证请求 future 和事件监听器各自收到数据
    @Test
    void dispatchesInterleavedEventAndResponse() throws Exception {
        try (var server = new ServerSocket(0)) {
            Thread fakeDaemon = Thread.ofVirtual().start(() -> serveInterleaved(server));
            var events = new CopyOnWriteArrayList<Event>();
            try (var client = new NdjsonRpcClient(
                    "127.0.0.1", server.getLocalPort(), Duration.ofSeconds(1), events::add)) {
                PongResult pong = client.request(
                                "core.ping", new PingCommand("test"), PongResult.class)
                        .get();

                assertThat(pong.serverVersion()).isEqualTo("test");
                assertThat(events).singleElement().isInstanceOf(RunStartedEvent.class);
            }
            fakeDaemon.join();
        }
    }

    // 发送事件与成功响应以模拟 daemon 混合流
    private static void serveInterleaved(ServerSocket server) {
        try (var socket = server.accept();
             var input = new BufferedReader(new InputStreamReader(
                     socket.getInputStream(), StandardCharsets.UTF_8));
             var output = new BufferedWriter(new OutputStreamWriter(
                     socket.getOutputStream(), StandardCharsets.UTF_8))) {
            String request = input.readLine();
            String id = io.klaude.protocol.ProtocolJson.mapper().readTree(request).path("id").asText();
            output.write("{\"kind\":\"event\",\"event\":{\"type\":\"run.started\","
                    + "\"run_id\":\"run-1\",\"goal\":\"goal\","
                    + "\"ts\":\"2026-07-20T00:00:00Z\"}}\n");
            output.write("{\"jsonrpc\":\"2.0\",\"id\":\"" + id + "\",\"result\":{"
                    + "\"server_version\":\"test\",\"uptime_ms\":1,"
                    + "\"received_at\":\"2026-07-20T00:00:00Z\"}}\n");
            output.flush();
        } catch (Exception error) {
            throw new RuntimeException(error);
        }
    }
}
