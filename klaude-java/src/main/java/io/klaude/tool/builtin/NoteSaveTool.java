package io.klaude.tool.builtin;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.klaude.protocol.ProtocolJson;
import io.klaude.tool.Tool;
import io.klaude.tool.ToolContext;
import io.klaude.tool.ToolErrorType;
import io.klaude.tool.ToolResult;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public final class NoteSaveTool implements Tool {
    private final NoteService service;

    // 初始化注入的笔记服务边界
    public NoteSaveTool(NoteService service) {
        this.service = Objects.requireNonNull(service, "service");
    }

    // 返回工具名
    @Override
    public String name() {
        return "note_save";
    }

    // 返回工具描述
    @Override
    public String description() {
        return "Save a durable note for the current session.";
    }

    // 返回笔记内容的输入 schema
    @Override
    public ObjectNode inputSchema() {
        var mapper = ProtocolJson.mapper();
        var schema = mapper.createObjectNode().put("type", "object");
        schema.set("properties", mapper.createObjectNode().set(
                "content", mapper.createObjectNode().put("type", "string")));
        schema.set("required", mapper.createArrayNode().add("content"));
        return schema;
    }

    // 保存去除边空白的 session 笔记
    @Override
    public CompletionStage<ToolResult> execute(ToolContext context, ObjectNode params) {
        try {
            String content = params.path("content").asText().strip();
            if (content.isEmpty()) {
                return CompletableFuture.completedFuture(
                        ToolResult.failure("empty content", ToolErrorType.RUNTIME_ERROR));
            }
            service.append(
                    context.sessionId(),
                    content,
                    context.runId());
            return CompletableFuture.completedFuture(ToolResult.success("saved"));
        } catch (Exception error) {
            return CompletableFuture.completedFuture(
                    ToolResult.failure(error.getMessage(), ToolErrorType.RUNTIME_ERROR));
        }
    }
}
