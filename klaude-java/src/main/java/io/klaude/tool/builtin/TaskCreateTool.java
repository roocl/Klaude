package io.klaude.tool.builtin;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.klaude.protocol.ProtocolJson;
import io.klaude.tool.Tool;
import io.klaude.tool.ToolContext;
import io.klaude.tool.ToolErrorType;
import io.klaude.tool.ToolResult;
import java.util.ArrayList;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public final class TaskCreateTool implements Tool {
    private final TaskCreateService service;

    // 初始化注入的任务服务边界
    public TaskCreateTool(TaskCreateService service) {
        this.service = Objects.requireNonNull(service, "service");
    }

    // 返回工具名
    @Override
    public String name() {
        return "task_create";
    }

    // 返回工具描述
    @Override
    public String description() {
        return "Create a task and return it as JSON.";
    }

    // 返回创建任务的输入 schema
    @Override
    public ObjectNode inputSchema() {
        var mapper = ProtocolJson.mapper();
        var properties = mapper.createObjectNode();
        properties.set("subject", mapper.createObjectNode().put("type", "string"));
        properties.set("description", mapper.createObjectNode().put("type", "string"));
        properties.set("blocked_by", mapper.createObjectNode()
                .put("type", "array")
                .set("items", mapper.createObjectNode().put("type", "integer")));
        var schema = mapper.createObjectNode().put("type", "object");
        schema.set("properties", properties);
        schema.set("required", mapper.createArrayNode().add("subject"));
        return schema;
    }

    // 调用注入服务创建任务并序列化结果
    @Override
    public CompletionStage<ToolResult> execute(ToolContext context, ObjectNode params) {
        try {
            var blockedBy = new ArrayList<Integer>();
            params.path("blocked_by").forEach(value -> blockedBy.add(value.intValue()));
            ObjectNode task = service.create(
                    params.path("subject").asText(),
                    params.path("description").asText(""),
                    java.util.List.copyOf(blockedBy));
            return CompletableFuture.completedFuture(
                    ToolResult.success(ProtocolJson.mapper().writeValueAsString(task)));
        } catch (Exception error) {
            return CompletableFuture.completedFuture(
                    ToolResult.failure(error.getMessage(), ToolErrorType.RUNTIME_ERROR));
        }
    }
}
