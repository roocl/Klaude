package io.klaude.daemon;

import static org.assertj.core.api.Assertions.assertThat;

import io.klaude.session.SessionStore;
import io.klaude.session.task.TaskManager;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class BuiltinToolsTest {
    // 功能：验证 daemon 将全部九个内置工具注册到稳定 registry
    // 设计：用临时 workspace、TaskManager 和 SessionStore 构建 registry 并观察 definitions 顺序
    @Test
    void registersCompleteBuiltinToolSet(@TempDir Path temp) throws Exception {
        var registry = BuiltinTools.create(
                temp.resolve("workspace"),
                new TaskManager(temp.resolve("tasks"), Clock.systemUTC()),
                new SessionStore(temp.resolve("sessions"), Clock.systemUTC()));

        assertThat(registry.definitions()).extracting(definition -> definition.name())
                .containsExactly(
                        "read_file",
                        "list_dir",
                        "write_file",
                        "bash",
                        "task_create",
                        "task_get",
                        "task_list",
                        "task_update",
                        "note_save");
    }

    // 功能：验证工具工厂用同一白名单过滤内置与扩展工具
    // 设计：只允许 read_file 与 fake extension，观察 registry 定义不含其他工具
    @Test
    void filtersBuiltinAndExtensionToolsWithOneAllowlist(@TempDir Path temp) throws Exception {
        var registry = BuiltinTools.create(
                temp.resolve("workspace"),
                new TaskManager(temp.resolve("tasks"), Clock.systemUTC()),
                new SessionStore(temp.resolve("sessions"), Clock.systemUTC()),
                List.of(new FakeTool()),
                Set.of("read_file", "remote__echo"));

        assertThat(registry.definitions()).extracting(definition -> definition.name())
                .containsExactly("read_file", "remote__echo");
    }

    private static final class FakeTool implements io.klaude.tool.Tool {
        // 返回 fake 扩展工具名
        @Override
        public String name() {
            return "remote__echo";
        }

        // 返回 fake 扩展工具描述
        @Override
        public String description() {
            return "echo";
        }

        // 返回空 object schema
        @Override
        public com.fasterxml.jackson.databind.node.ObjectNode inputSchema() {
            return io.klaude.protocol.ProtocolJson.mapper().createObjectNode()
                    .put("type", "object");
        }

        // 返回固定成功结果
        @Override
        public java.util.concurrent.CompletionStage<io.klaude.tool.ToolResult> execute(
                io.klaude.tool.ToolContext context,
                com.fasterxml.jackson.databind.node.ObjectNode params) {
            return java.util.concurrent.CompletableFuture.completedFuture(
                    io.klaude.tool.ToolResult.success("ok"));
        }
    }
}
