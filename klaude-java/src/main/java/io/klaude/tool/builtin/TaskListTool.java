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

public final class TaskListTool implements Tool {
    private final TaskListService service;

    // 初始化注入的任务列表服务边界
    public TaskListTool(TaskListService service) {
        this.service = Objects.requireNonNull(service, "service");
    }

    // 返回工具名
    @Override
    public String name() {
        return "task_list";
    }

    // 返回工具描述
    @Override
    public String description() {
        return "List all tasks and their current status.";
    }

    // 返回无参数 object schema
    @Override
    public ObjectNode inputSchema() {
        var mapper = ProtocolJson.mapper();
        var schema = mapper.createObjectNode().put("type", "object");
        schema.set("properties", mapper.createObjectNode());
        return schema;
    }

    // 返回注入服务生成的任务列表
    @Override
    public CompletionStage<ToolResult> execute(ToolContext context, ObjectNode params) {
        try {
            return CompletableFuture.completedFuture(ToolResult.success(service.list()));
        } catch (Exception error) {
            return CompletableFuture.completedFuture(
                    ToolResult.failure(error.getMessage(), ToolErrorType.RUNTIME_ERROR));
        }
    }
}
