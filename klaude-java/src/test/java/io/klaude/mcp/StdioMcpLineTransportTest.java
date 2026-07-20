package io.klaude.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.klaude.protocol.ProtocolJson;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

final class StdioMcpLineTransportTest {
    // 功能：验证 stdio fake process 在大量 stderr 下完成 initialize/list/call 并在 close 后退出
    // 设计：启动当前 JDK 的 fixture main，通过真实 pipes 使用公开 MCP client 后审计 process lifecycle
    @Test
    @Timeout(15)
    void servesToolsAndDrainsStderrBeforeClosingProcess() throws Exception {
        String java = Path.of(
                System.getProperty("java.home"), "bin", "java.exe").toString();
        var transport = StdioMcpLineTransport.start(
                List.of(
                        java,
                        "-cp",
                        System.getProperty("java.class.path"),
                        McpFixtureMain.class.getName()),
                Map.of(),
                64 * 1024 * 1024);
        McpClient client = McpClient.connect(transport, Duration.ofSeconds(3))
                .toCompletableFuture().get();

        assertThat(client.listTools().toCompletableFuture().get())
                .singleElement().extracting(McpToolDefinition::name).isEqualTo("echo");
        assertThat(client.callTool("echo", ProtocolJson.mapper().createObjectNode())
                .toCompletableFuture().get()).isEqualTo("stdio ok");

        client.close();
        assertThat(transport.awaitExit(Duration.ofSeconds(3))).isTrue();
        assertThat(transport.isAlive()).isFalse();
    }

    // 功能：验证 fake process 在 initialize 后 EOF 被分类为 server unavailable 并退出
    // 设计：fixture eof 模式不写响应，通过公开 connect future 观察异常并审计 process
    @Test
    @Timeout(10)
    void classifiesStdioEofAndLeavesNoProcess() throws Exception {
        var transport = startFixture("eof");

        assertThatThrownBy(() -> McpClient.connect(transport, Duration.ofSeconds(2))
                        .toCompletableFuture().join())
                .hasRootCauseInstanceOf(McpServerUnavailableException.class)
                .hasRootCauseMessage("MCP server closed connection");
        transport.close();
        assertThat(transport.awaitExit(Duration.ofSeconds(2))).isTrue();
        assertThat(transport.isAlive()).isFalse();
    }

    // 功能：验证 stdio read timeout 自动终止无响应 fake process
    // 设计：fixture timeout 模式阻塞 initialize，client 使用 100ms 时限并审计 process 已退出
    @Test
    @Timeout(10)
    void timesOutAndTerminatesUnresponsiveProcess() throws Exception {
        var transport = startFixture("timeout");

        assertThatThrownBy(() -> McpClient.connect(transport, Duration.ofMillis(100))
                        .toCompletableFuture().join())
                .hasRootCauseInstanceOf(McpServerUnavailableException.class)
                .hasRootCauseMessage("MCP server read timeout");
        assertThat(transport.awaitExit(Duration.ofSeconds(2))).isTrue();
        assertThat(transport.isAlive()).isFalse();
    }

    // 功能：验证 stdio fake process 的 tools/call error 保留 application error 分类
    // 设计：fixture error 模式正常握手后返回 JSON-RPC error，观察异常再正常关闭 process
    @Test
    @Timeout(10)
    void returnsApplicationErrorWithoutBreakingStdioConnection() throws Exception {
        var transport = startFixture("error");
        McpClient client = McpClient.connect(transport, Duration.ofSeconds(2))
                .toCompletableFuture().get();

        assertThatThrownBy(() -> client.callTool("echo", ProtocolJson.mapper().createObjectNode())
                        .toCompletableFuture().join())
                .hasRootCauseInstanceOf(McpToolException.class)
                .hasRootCauseMessage("fixture error (code=-32001)");
        client.close();
        assertThat(transport.awaitExit(Duration.ofSeconds(2))).isTrue();
    }

    // 启动指定模式的当前 JDK fake MCP process
    private static StdioMcpLineTransport startFixture(String mode) throws Exception {
        String java = Path.of(
                System.getProperty("java.home"), "bin", "java.exe").toString();
        return StdioMcpLineTransport.start(
                List.of(
                        java,
                        "-cp",
                        System.getProperty("java.class.path"),
                        McpFixtureMain.class.getName(),
                        mode),
                Map.of(),
                64 * 1024 * 1024);
    }
}
