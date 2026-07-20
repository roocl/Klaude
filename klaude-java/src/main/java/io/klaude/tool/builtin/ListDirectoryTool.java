package io.klaude.tool.builtin;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.klaude.protocol.ProtocolJson;
import io.klaude.tool.Tool;
import io.klaude.tool.ToolContext;
import io.klaude.tool.ToolErrorType;
import io.klaude.tool.ToolResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public final class ListDirectoryTool implements Tool {
    private final WorkspacePaths paths;

    // 初始化显式 workspace root
    public ListDirectoryTool(Path workspaceRoot) throws IOException {
        this.paths = new WorkspacePaths(workspaceRoot);
    }

    // 返回工具名
    @Override
    public String name() {
        return "list_dir";
    }

    // 返回工具描述
    @Override
    public String description() {
        return "List files under one workspace directory.";
    }

    // 返回 path/default max_depth 参数 schema
    @Override
    public ObjectNode inputSchema() {
        var mapper = ProtocolJson.mapper();
        var schema = mapper.createObjectNode().put("type", "object");
        var properties = mapper.createObjectNode();
        properties.set("path", mapper.createObjectNode().put("type", "string").put("default", "."));
        properties.set("max_depth", mapper.createObjectNode()
                .put("type", "integer").put("minimum", 1).put("maximum", 4).put("default", 2));
        schema.set("properties", properties);
        return schema;
    }

    // 以稳定相对路径顺序列出 workspace 目录树
    @Override
    public CompletionStage<ToolResult> execute(ToolContext context, ObjectNode params) {
        try {
            Path directory = paths.existing(params.path("path").asText("."));
            if (!Files.isDirectory(directory)) {
                throw new IOException("not a directory: " + directory);
            }
            int maxDepth = params.path("max_depth").asInt(2);
            try (var stream = Files.walk(directory, maxDepth)) {
                String content = stream
                        .filter(path -> !path.equals(directory))
                        .map(path -> format(directory, path))
                        .sorted()
                        .collect(java.util.stream.Collectors.joining("\n"));
                return CompletableFuture.completedFuture(ToolResult.success(content));
            }
        } catch (Exception error) {
            return CompletableFuture.completedFuture(
                    ToolResult.failure(error.getMessage(), ToolErrorType.RUNTIME_ERROR));
        }
    }

    // 将路径格式化为 slash 分隔的相对列表项
    private static String format(Path directory, Path path) {
        String relative = directory.relativize(path).toString().replace('\\', '/');
        return Files.isDirectory(path) ? relative + "/" : relative;
    }
}
