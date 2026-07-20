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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public final class ReadFileTool implements Tool {
    private final WorkspacePaths paths;

    // 初始化显式 workspace root
    public ReadFileTool(Path workspaceRoot) throws IOException {
        this.paths = new WorkspacePaths(workspaceRoot);
    }

    // 返回工具名
    @Override
    public String name() {
        return "read_file";
    }

    // 返回工具描述
    @Override
    public String description() {
        return "Read one UTF-8 file inside the workspace.";
    }

    // 返回 path string 参数 schema
    @Override
    public ObjectNode inputSchema() {
        var mapper = ProtocolJson.mapper();
        var schema = mapper.createObjectNode().put("type", "object");
        schema.set("properties", mapper.createObjectNode().set(
                "path", mapper.createObjectNode().put("type", "string")));
        schema.set("required", mapper.createArrayNode().add("path"));
        return schema;
    }

    // 读取 workspace 内 UTF-8 文件并将 I/O 异常转为 ToolResult
    @Override
    public CompletionStage<ToolResult> execute(ToolContext context, ObjectNode params) {
        try {
            Path path = paths.existing(params.path("path").asText());
            if (!Files.isRegularFile(path)) {
                throw new IOException("not a regular file: " + path);
            }
            return CompletableFuture.completedFuture(
                    ToolResult.success(Files.readString(path, StandardCharsets.UTF_8)));
        } catch (Exception error) {
            return CompletableFuture.completedFuture(
                    ToolResult.failure(error.getMessage(), ToolErrorType.RUNTIME_ERROR));
        }
    }
}
