package io.klaude.tool.builtin;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.klaude.protocol.ProtocolJson;
import io.klaude.tool.Tool;
import io.klaude.tool.ToolContext;
import io.klaude.tool.ToolErrorType;
import io.klaude.tool.ToolResult;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public final class WriteFileTool implements Tool {
    private final WorkspacePaths paths;

    // 初始化显式 workspace root
    public WriteFileTool(Path workspaceRoot) throws IOException {
        this.paths = new WorkspacePaths(workspaceRoot);
    }

    // 返回工具名
    @Override
    public String name() {
        return "write_file";
    }

    // 返回工具描述
    @Override
    public String description() {
        return "Atomically write one UTF-8 file inside the workspace.";
    }

    // 返回 path/content string 参数 schema
    @Override
    public ObjectNode inputSchema() {
        var mapper = ProtocolJson.mapper();
        var schema = mapper.createObjectNode().put("type", "object");
        var properties = mapper.createObjectNode();
        properties.set("path", mapper.createObjectNode().put("type", "string"));
        properties.set("content", mapper.createObjectNode().put("type", "string"));
        schema.set("properties", properties);
        schema.set("required", mapper.createArrayNode().add("path").add("content"));
        return schema;
    }

    // 通过同目录临时文件和原子替换写入 UTF-8 内容
    @Override
    public CompletionStage<ToolResult> execute(ToolContext context, ObjectNode params) {
        Path temporary = null;
        try {
            Path target = paths.writable(params.path("path").asText());
            Files.createDirectories(target.getParent());
            temporary = target.resolveSibling(target.getFileName() + ".tmp-" + UUID.randomUUID());
            Files.writeString(
                    temporary,
                    params.path("content").asText(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE);
            Files.move(
                    temporary,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            return CompletableFuture.completedFuture(
                    ToolResult.success("Wrote " + target));
        } catch (Exception error) {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // Preserve the original write error.
                }
            }
            return CompletableFuture.completedFuture(
                    ToolResult.failure(error.getMessage(), ToolErrorType.RUNTIME_ERROR));
        }
    }
}
