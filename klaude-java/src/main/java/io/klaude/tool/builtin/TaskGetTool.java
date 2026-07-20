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

public final class TaskGetTool implements Tool {
    private final TaskGetService service;

    // 初始化注入的任务查询服务边界
    public TaskGetTool(TaskGetService service) {
        this.service = Objects.requireNonNull(service, "service");
    }

    // 返回工具名
    @Override
    public String name() {
        return "task_get";
    }

    // 返回工具描述
    @Override
    public String description() {
        return "Get one task by its integer ID.";
    }

    // 返回 task_id 整数参数 schema
    @Override
    public ObjectNode inputSchema() {
        var mapper = ProtocolJson.mapper();
        var schema = mapper.createObjectNode().put("type", "object");
        schema.set("properties", mapper.createObjectNode().set(
                "task_id", mapper.createObjectNode().put("type", "integer")));
        schema.set("required", mapper.createArrayNode().add("task_id"));
        return schema;
    }

    // 查询任务并序列化服务返回的 JSON
    @Override
    public CompletionStage<ToolResult> execute(ToolContext context, ObjectNode params) {
        try {
            ObjectNode task = service.get(params.path("task_id").intValue());
            return CompletableFuture.completedFuture(
                    ToolResult.success(ProtocolJson.mapper().writeValueAsString(task)));
        } catch (Exception error) {
            return CompletableFuture.completedFuture(
                    ToolResult.failure(error.getMessage(), ToolErrorType.RUNTIME_ERROR));
        }
    }
}
