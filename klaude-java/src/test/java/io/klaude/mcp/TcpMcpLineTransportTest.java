package io.klaude.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

final class TcpMcpLineTransportTest {
    // 功能：验证 TCP transport 通过 ephemeral fake server 完成 MCP handshake 和工具发现
    // 设计：本地 ServerSocket 按请求顺序返回 initialize/list，client close 后等待 server 正常退出
    @Test
    @Timeout(10)
    void initializesAndListsToolsOverTcp() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            var served = CompletableFuture.runAsync(() -> serve(server));
            var transport = TcpMcpLineTransport.connect(
                    "127.0.0.1", server.getLocalPort(), 64 * 1024 * 1024);
            McpClient client = McpClient.connect(transport, Duration.ofSeconds(2))
                    .toCompletableFuture().get();

            assertThat(client.listTools().toCompletableFuture().get())
                    .singleElement()
                    .extracting(McpToolDefinition::name)
                    .isEqualTo("echo");

            client.close();
            served.get(3, TimeUnit.SECONDS);
        }
    }

    // 功能：验证 TCP transport 在 read timeout 后返回 server unavailable 且可立即关闭
    // 设计：ephemeral server 只 accept 不写数据，客户端使用 100ms timeout 观察异常分类
    @Test
    @Timeout(10)
    void classifiesTcpReadTimeoutAndClosesCleanly() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            var accepted = CompletableFuture.runAsync(() -> {
                try (var socket = server.accept()) {
                    while (socket.getInputStream().read() >= 0) {
                        // Wait until client close proves the blocked read is released.
                    }
                } catch (Exception error) {
                    throw new java.util.concurrent.CompletionException(error);
                }
            });
            var transport = TcpMcpLineTransport.connect(
                    "127.0.0.1", server.getLocalPort(), 64 * 1024 * 1024);

            assertThatThrownBy(() -> transport.readLine(Duration.ofMillis(100))
                            .toCompletableFuture().join())
                    .cause()
                    .isInstanceOf(McpServerUnavailableException.class)
                    .hasMessage("MCP server read timeout");
            transport.close();
            accepted.get(3, TimeUnit.SECONDS);
        }
    }

    // 按 MCP 顺序服务 initialize、initialized notification 与 tools/list
    private static void serve(ServerSocket server) {
        try (var socket = server.accept();
             var reader = new BufferedReader(new InputStreamReader(
                     socket.getInputStream(), StandardCharsets.UTF_8));
             var writer = new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8)) {
            reader.readLine();
            writer.write("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}\n");
            writer.flush();
            reader.readLine();
            reader.readLine();
            writer.write("{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"tools\":[{"
                    + "\"name\":\"echo\",\"description\":\"echo\","
                    + "\"inputSchema\":{\"type\":\"object\"}}]}}\n");
            writer.flush();
        } catch (Exception error) {
            throw new java.util.concurrent.CompletionException(error);
        }
    }
}
