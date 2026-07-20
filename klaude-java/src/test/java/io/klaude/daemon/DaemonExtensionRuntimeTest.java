package io.klaude.daemon;

import static org.assertj.core.api.Assertions.assertThat;

import io.klaude.daemon.config.RuntimeConfig;
import io.klaude.mcp.McpConnection;
import io.klaude.mcp.McpServerSpec;
import io.klaude.mcp.McpToolDefinition;
import io.klaude.protocol.ProtocolJson;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class DaemonExtensionRuntimeTest {
    // 功能：验证生产 runtime 使用 classpath built-ins 与 .klaude 覆盖根目录
    // 设计：临时 cwd/home 创建 production runtime，直接解析内建 review skill
    @Test
    void productionRuntimeLoadsClasspathBuiltins(@TempDir Path temp) {
        var runtime = DaemonExtensionRuntime.production(
                temp.resolve("workspace"),
                temp.resolve("home"),
                spec -> CompletableFuture.failedFuture(new IllegalStateException("unused")));

        var prompt = runtime.resolvePrompt("/review src/Main.java");

        assertThat(prompt.goal()).contains("src/Main.java");
        assertThat(prompt.skillName()).isEqualTo("review");
        runtime.close();
    }

    // 功能：验证扩展 runtime 映射 MCP 配置、隔离失败 server 并解析项目 skill
    // 设计：临时根写 skill，fake connector 捕获 spec 且仅健康连接返回工具
    @Test
    void startsConfiguredMcpAndResolvesProjectSkill(@TempDir Path temp) throws Exception {
        Path projectSkills = temp.resolve("project/skills");
        Files.createDirectories(projectSkills);
        Files.writeString(projectSkills.resolve("review.md"), """
                ---
                name: review
                description: review files
                allowed_tools: [read_file]
                ---
                Review $ARGUMENTS
                """);
        var captured = new AtomicReference<McpServerSpec>();
        var healthy = new FakeConnection();
        var runtime = new DaemonExtensionRuntime(
                projectSkills,
                temp.resolve("user/skills"),
                temp.resolve("builtin/skills"),
                temp.resolve("project/agents"),
                temp.resolve("user/agents"),
                temp.resolve("builtin/agents"),
                spec -> {
                    captured.set(spec);
                    return spec.name().equals("bad")
                            ? CompletableFuture.failedFuture(new IllegalStateException("offline"))
                            : CompletableFuture.completedFuture(healthy);
                });
        var servers = List.of(
                new RuntimeConfig.McpServer(
                        "bad", "tcp", "", List.of(), Map.of(), "127.0.0.1", 1),
                new RuntimeConfig.McpServer(
                        "good", "stdio", "java", List.of("-version"),
                        Map.of("MODE", "test"), "", 0));

        runtime.start(servers).toCompletableFuture().get();
        var prompt = runtime.resolvePrompt("/review src/Main.java");

        assertThat(captured.get().command()).containsExactly("java", "-version");
        assertThat(captured.get().environment()).containsEntry("MODE", "test");
        assertThat(runtime.tools()).singleElement()
                .satisfies(tool -> assertThat(tool.name()).isEqualTo("good__echo"));
        assertThat(prompt.goal()).isEqualTo("Review src/Main.java");
        assertThat(prompt.systemPromptOverride()).isEqualTo("Review $ARGUMENTS");
        assertThat(prompt.allowedTools()).containsExactly("read_file");
        runtime.close();
        assertThat(healthy.closed).isTrue();
    }

    private static final class FakeConnection implements McpConnection {
        private boolean closed;

        // 返回固定 echo 工具定义
        @Override
        public java.util.concurrent.CompletionStage<List<McpToolDefinition>> listTools() {
            return CompletableFuture.completedFuture(List.of(new McpToolDefinition(
                    "echo", "Echo", ProtocolJson.mapper().createObjectNode()
                            .put("type", "object"))));
        }

        // 返回固定 fake 调用结果
        @Override
        public java.util.concurrent.CompletionStage<String> callTool(
                String name, com.fasterxml.jackson.databind.node.ObjectNode arguments) {
            return CompletableFuture.completedFuture("ok");
        }

        // 记录 connection 已关闭
        @Override
        public void close() {
            closed = true;
        }
    }
}
