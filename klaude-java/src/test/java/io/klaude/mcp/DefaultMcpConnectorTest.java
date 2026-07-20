package io.klaude.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class DefaultMcpConnectorTest {
    // 功能：验证默认 connector 可启动 stdio server 并完成握手与工具发现
    // 设计：用当前 Java 启动本地 fixture process，经公开 connector 接口列出 echo 工具
    @Test
    void connectsConfiguredStdioServer() throws Exception {
        var connector = new DefaultMcpConnector();
        var spec = McpServerSpec.stdio("local", fixtureCommand("normal"), Map.of());

        try (McpConnection connection = connector.connect(spec).toCompletableFuture().get()) {
            assertThat(connection.listTools().toCompletableFuture().get())
                    .singleElement()
                    .extracting(McpToolDefinition::name)
                    .isEqualTo("echo");
        }
    }

    // 功能：验证默认 connector 在握手 EOF 时关闭已启动 stdio process
    // 设计：fixture 收到 initialize 后退出，通过失败结果及活动 fixture process 数观察清理
    @Test
    void closesStdioTransportWhenHandshakeFails() {
        long before = fixtureProcesses();
        var connector = new DefaultMcpConnector(64 * 1024 * 1024, Duration.ofSeconds(2));
        var spec = McpServerSpec.stdio("eof", fixtureCommand("eof"), Map.of());

        assertThatThrownBy(() -> connector.connect(spec).toCompletableFuture().join())
                .hasRootCauseInstanceOf(McpServerUnavailableException.class);

        for (int attempt = 0; attempt < 100 && fixtureProcesses() > before; attempt++) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        assertThat(fixtureProcesses()).isLessThanOrEqualTo(before);
    }

    // 返回启动本地 MCP fixture 的 Java 命令
    private static List<String> fixtureCommand(String mode) {
        return List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp",
                System.getProperty("java.class.path"),
                McpFixtureMain.class.getName(),
                mode);
    }

    // 返回当前仍存活的 MCP fixture process 数
    private static long fixtureProcesses() {
        return ProcessHandle.allProcesses()
                .filter(ProcessHandle::isAlive)
                .filter(process -> process.info().commandLine()
                        .orElse("")
                        .contains(McpFixtureMain.class.getName()))
                .count();
    }
}
