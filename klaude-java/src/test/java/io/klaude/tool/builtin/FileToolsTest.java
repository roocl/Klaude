package io.klaude.tool.builtin;

import static org.assertj.core.api.Assertions.assertThat;

import io.klaude.protocol.ProtocolJson;
import io.klaude.tool.ToolContext;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class FileToolsTest {
    // 功能：验证 read/list/write 使用 UTF-8 workspace root 且拒绝路径逃逸
    // 设计：在临时 workspace 读中文、列目录、写嵌套文件，再尝试 ../escape 并观察错误结果
    @Test
    void readsListsWritesAndRejectsOutsideWorkspace(@TempDir Path temp) throws Exception {
        Path workspace = temp.resolve("workspace");
        Files.createDirectories(workspace.resolve("docs"));
        Files.writeString(workspace.resolve("docs/说明.txt"), "中文内容", StandardCharsets.UTF_8);
        ToolContext context = new ToolContext(workspace, "session-001", "run-001", "tool-001");

        var read = new ReadFileTool(workspace).execute(
                context,
                ProtocolJson.mapper().createObjectNode().put("path", "docs/说明.txt"))
                .toCompletableFuture().get();
        var list = new ListDirectoryTool(workspace).execute(
                context,
                ProtocolJson.mapper().createObjectNode().put("path", ".").put("max_depth", 3))
                .toCompletableFuture().get();
        var write = new WriteFileTool(workspace).execute(
                context,
                ProtocolJson.mapper().createObjectNode()
                        .put("path", "output/结果.txt")
                        .put("content", "写入成功"))
                .toCompletableFuture().get();
        var escape = new WriteFileTool(workspace).execute(
                context,
                ProtocolJson.mapper().createObjectNode()
                        .put("path", "../escape.txt")
                        .put("content", "forbidden"))
                .toCompletableFuture().get();

        assertThat(read.content()).isEqualTo("中文内容");
        assertThat(list.content()).contains("docs/", "docs/说明.txt");
        assertThat(write.isError()).isFalse();
        assertThat(Files.readString(workspace.resolve("output/结果.txt"), StandardCharsets.UTF_8))
                .isEqualTo("写入成功");
        assertThat(escape.isError()).isTrue();
        assertThat(temp.resolve("escape.txt")).doesNotExist();
    }

    // 功能：验证 write_file 拒绝经 workspace 内符号链接逃逸到外部目录
    // 设计：在支持 symlink 的环境中链接外部临时目录，写入后观察错误且外部无文件
    @Test
    void rejectsSymlinkEscapeWhenSupported(@TempDir Path temp) throws Exception {
        Path workspace = Files.createDirectories(temp.resolve("workspace"));
        Path outside = Files.createDirectories(temp.resolve("outside"));
        try {
            Files.createSymbolicLink(workspace.resolve("outside-link"), outside);
        } catch (UnsupportedOperationException | IOException | SecurityException error) {
            Assumptions.assumeTrue(false, "symbolic links unavailable: " + error.getMessage());
        }

        var result = new WriteFileTool(workspace).execute(
                        new ToolContext(workspace, "session-001", "run-001", "tool-001"),
                        ProtocolJson.mapper().createObjectNode()
                                .put("path", "outside-link/escape.txt")
                                .put("content", "forbidden"))
                .toCompletableFuture().get();

        assertThat(result.isError()).isTrue();
        assertThat(outside.resolve("escape.txt")).doesNotExist();
    }
}
