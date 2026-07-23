package io.klaude.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.klaude.protocol.ProtocolJson;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

final class TuiConnectionTest {
    // 功能：Socket 意外关闭后自动重连并重新订阅事件
    // 设计：fake daemon 接受两条连接，第一条订阅后断开，第二条返回 replay 计数
    @Test
    void reconnectsAndResubscribesAfterDisconnect() throws Exception {
        try (var server = new ServerSocket(0)) {
            Thread daemon = Thread.ofVirtual().start(() -> serveTwoConnections(server));
            var model = new TuiModel();
            var renderer = new TuiRenderer(
                    new PrintStream(new ByteArrayOutputStream()), false, 80, 24);
            var endpoint = new CliEndpoint(
                    "127.0.0.1", server.getLocalPort(), Path.of("trace.jsonl"));

            try (var connection = new TuiConnection(endpoint, model, renderer)) {
                connection.connect();
                long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
                while (System.nanoTime() < deadline
                        && java.util.Arrays.stream(model.snapshot().transcript())
                                .noneMatch(line -> line.contains("reconnected"))) {
                    Thread.sleep(20);
                }

                assertThat(model.snapshot().connected()).isTrue();
                assertThat(model.snapshot().transcript())
                        .anyMatch(line -> line.contains("reconnected; replayed 2 events"));
            }
            daemon.join(TimeUnit.SECONDS.toMillis(2));
        }
    }

    // 依次服务初始连接和恢复连接
    private static void serveTwoConnections(ServerSocket server) {
        try {
            for (int connection = 0; connection < 2; connection++) {
                try (var socket = server.accept();
                     var input = new BufferedReader(new InputStreamReader(
                             socket.getInputStream(), StandardCharsets.UTF_8));
                     var output = new BufferedWriter(new OutputStreamWriter(
                             socket.getOutputStream(), StandardCharsets.UTF_8))) {
                    respond(input, output, 0);
                    respond(input, output, connection == 0 ? 0 : 2);
                    if (connection == 1) {
                        input.readLine();
                    }
                }
            }
        } catch (Exception error) {
            throw new RuntimeException(error);
        }
    }

    // 回复一条 ping 或 subscribe 请求
    private static void respond(BufferedReader input, BufferedWriter output, int replayed)
            throws Exception {
        var request = ProtocolJson.mapper().readTree(input.readLine());
        String id = request.path("id").asText();
        if (request.path("method").asText().equals("core.ping")) {
            output.write("{\"jsonrpc\":\"2.0\",\"id\":\"" + id + "\",\"result\":{"
                    + "\"server_version\":\"test\",\"uptime_ms\":1,"
                    + "\"received_at\":\"2026-01-01T00:00:00Z\"}}\n");
        } else {
            output.write("{\"jsonrpc\":\"2.0\",\"id\":\"" + id + "\",\"result\":{"
                    + "\"subscription_id\":\"sub-test\",\"replayed_count\":"
                    + replayed + "}}\n");
        }
        output.flush();
    }
}
