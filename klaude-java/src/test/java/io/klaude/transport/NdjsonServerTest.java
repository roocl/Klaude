package io.klaude.transport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.klaude.protocol.ProtocolJson;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

final class NdjsonServerTest {
    // 功能：验证真实 TCP 连接能发送 core.ping NDJSON 并收到完整 JSON-RPC success 行
    // 设计：server 绑定 ephemeral port，公开 handler 回写固定 pong，客户端只使用 JDK Socket 观察 wire JSON
    @Test
    void roundTripsPingOverRealSocket() throws Exception {
        var mapper = ProtocolJson.mapper();
        try (NdjsonServer server = new NdjsonServer(
                "127.0.0.1",
                0,
                64 * 1024 * 1024,
                (connection, line) -> {
                    var request = mapper.readTree(line);
                    var response = mapper.createObjectNode();
                    response.put("jsonrpc", "2.0");
                    response.set("id", request.get("id"));
                    response.set("result", mapper.createObjectNode()
                            .put("server_version", "0.1.0")
                            .put("uptime_ms", 0)
                            .put("received_at", "2026-07-19T10:15:30Z"));
                    return connection.send(response);
                })) {
            server.start().get(Duration.ofSeconds(5).toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
            try (Socket socket = new Socket("127.0.0.1", server.port());
                 var writer = new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8);
                 var reader = new BufferedReader(new InputStreamReader(
                         socket.getInputStream(), StandardCharsets.UTF_8))) {
                socket.setSoTimeout(5_000);
                writer.write("{\"jsonrpc\":\"2.0\",\"id\":\"ping-1\",\"method\":\"core.ping\","
                        + "\"params\":{\"client\":\"java-test\"}}\n");
                writer.flush();

                var response = mapper.readTree(reader.readLine());

                assertThat(response.path("id").asText()).isEqualTo("ping-1");
                assertThat(response.path("result").path("server_version").asText()).isEqualTo("0.1.0");
            }
        }
    }

    // 功能：验证半包、粘包和单次多行输入均按换行边界生成独立 frame
    // 设计：第一条请求拆成两次 write，随后一次 write 两条请求，并比较收到的三个独立 ID
    @Test
    void framesPartialAndCoalescedInput() throws Exception {
        var mapper = ProtocolJson.mapper();
        try (NdjsonServer server = new NdjsonServer(
                "127.0.0.1",
                0,
                64 * 1024 * 1024,
                (connection, line) -> {
                    var request = mapper.readTree(line);
                    var response = mapper.createObjectNode().put("jsonrpc", "2.0");
                    response.set("id", request.get("id"));
                    response.set("result", mapper.createObjectNode());
                    return connection.send(response);
                })) {
            server.start().get(5, java.util.concurrent.TimeUnit.SECONDS);
            try (Socket socket = new Socket("127.0.0.1", server.port());
                 var writer = new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8);
                 var reader = new BufferedReader(new InputStreamReader(
                         socket.getInputStream(), StandardCharsets.UTF_8))) {
                socket.setSoTimeout(5_000);
                writer.write("{\"jsonrpc\":\"2.0\",\"id\":\"one\"");
                writer.flush();
                writer.write(",\"method\":\"core.ping\",\"params\":{}}\n");
                writer.write("{\"jsonrpc\":\"2.0\",\"id\":\"two\",\"method\":\"core.ping\",\"params\":{}}\n"
                        + "{\"jsonrpc\":\"2.0\",\"id\":\"three\",\"method\":\"core.ping\",\"params\":{}}\n");
                writer.flush();

                List<String> ids = new ArrayList<>();
                for (int index = 0; index < 3; index++) {
                    ids.add(mapper.readTree(reader.readLine()).path("id").asText());
                }

                assertThat(ids).containsExactlyInAnyOrder("one", "two", "three");
            }
        }
    }

    // 功能：验证端口占用会使第二次绑定失败，原 server 关闭后同一端口可立即重用
    // 设计：依次启动 first、冲突 second、关闭 first、启动 third，并确保每个失败/成功实例都显式关闭
    @Test
    void rejectsOccupiedPortAndRebindsAfterClose() throws Exception {
        NdjsonLineHandler noOp = (connection, line) -> CompletableFuture.completedFuture(null);
        NdjsonServer first = new NdjsonServer("127.0.0.1", 0, 1024, noOp);
        NdjsonServer second = null;
        try {
            first.start().get(5, java.util.concurrent.TimeUnit.SECONDS);
            int port = first.port();
            second = new NdjsonServer("127.0.0.1", port, 1024, noOp);
            NdjsonServer occupied = second;

            assertThatThrownBy(() -> occupied.start().get(
                    5, java.util.concurrent.TimeUnit.SECONDS))
                    .hasRootCauseInstanceOf(java.net.BindException.class);

            second.close();
            second = null;
            first.close();
            try (NdjsonServer third = new NdjsonServer("127.0.0.1", port, 1024, noOp)) {
                third.start().get(5, java.util.concurrent.TimeUnit.SECONDS);
                assertThat(third.port()).isEqualTo(port);
            }
        } finally {
            if (second != null) {
                second.close();
            }
            first.close();
        }
    }
}
