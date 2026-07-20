package io.klaude.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.klaude.protocol.ProtocolJson;
import io.klaude.tool.Tool;
import io.klaude.tool.ToolContext;
import io.klaude.tool.ToolErrorType;
import io.klaude.tool.ToolResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public final class TaskUpdateTool implements Tool {
    private final TaskUpdateService service;

    // 初始化注入的任务更新服务边界
    public TaskUpdateTool(TaskUpdateService service) {
        this.service = Objects.requireNonNull(service, "service");
    }

    // 返回工具名
    @Override
    public String name() {
        return "task_update";
    }

    // 返回工具描述
    @Override
    public String description() {
        return "Update a task status or blocking dependencies.";
    }

    // 返回任务更新参数 schema
    @Override
    public ObjectNode inputSchema() {
        var mapper = ProtocolJson.mapper();
        var properties = mapper.createObjectNode();
        properties.set("task_id", mapper.createObjectNode().put("type", "integer"));
        properties.set("status", mapper.createObjectNode()
                .put("type", "string")
                .set("enum", mapper.createArrayNode()
                        .add("pending").add("in_progress").add("completed")));
        ObjectNode integerArray = mapper.createObjectNode().put("type", "array");
        integerArray.set("items", mapper.createObjectNode().put("type", "integer"));
        properties.set("add_blocked_by", integerArray.deepCopy());
        properties.set("remove_blocked_by", integerArray.deepCopy());
        var schema = mapper.createObjectNode().put("type", "object");
        schema.set("properties", properties);
        schema.set("required", mapper.createArrayNode().add("task_id"));
        return schema;
    }

    // 映射参数、更新任务并序列化返回 JSON
    @Override
    public CompletionStage<ToolResult> execute(ToolContext context, ObjectNode params) {
        try {
            var request = new TaskUpdateRequest(
                    params.path("task_id").intValue(),
                    params.has("status") ? params.path("status").asText() : null,
                    integers(params.path("add_blocked_by")),
                    integers(params.path("remove_blocked_by")));
            ObjectNode task = service.update(request);
            return CompletableFuture.completedFuture(
                    ToolResult.success(ProtocolJson.mapper().writeValueAsString(task)));
        } catch (Exception error) {
            return CompletableFuture.completedFuture(
                    ToolResult.failure(error.getMessage(), ToolErrorType.RUNTIME_ERROR));
        }
    }

    // 将 JSON array 转为不可变整数列表
    private static List<Integer> integers(JsonNode values) {
        var result = new ArrayList<Integer>();
        values.forEach(value -> result.add(value.intValue()));
        return List.copyOf(result);
    }
}
