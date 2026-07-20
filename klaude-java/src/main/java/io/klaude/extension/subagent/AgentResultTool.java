package io.klaude.extension.subagent;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.klaude.protocol.ProtocolJson;
import io.klaude.tool.Tool;
import io.klaude.tool.ToolContext;
import io.klaude.tool.ToolErrorType;
import io.klaude.tool.ToolResult;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public final class AgentResultTool implements Tool {
    private final BackgroundSubagentRegistry registry;

    // 初始化共享后台 subagent registry
    public AgentResultTool(BackgroundSubagentRegistry registry) {
        this.registry = java.util.Objects.requireNonNull(registry, "registry");
    }

    // 返回 agent_result 工具名
    @Override
    public String name() {
        return "agent_result";
    }

    // 返回后台 child 查询行为描述
    @Override
    public String description() {
        return "Retrieve one background sub-agent result.";
    }

    // 返回 run_id 参数 JSON Schema
    @Override
    public ObjectNode inputSchema() {
        ObjectNode schema = ProtocolJson.mapper().createObjectNode().put("type", "object");
        schema.set("properties", ProtocolJson.mapper().createObjectNode()
                .set("run_id", ProtocolJson.mapper().createObjectNode().put("type", "string")));
        schema.set("required", ProtocolJson.mapper().createArrayNode().add("run_id"));
        return schema;
    }

    // 查询后台 child 并仅消费一次完成结果
    @Override
    public CompletionStage<ToolResult> execute(ToolContext context, ObjectNode params) {
        String runId = params.path("run_id").asText();
        BackgroundSubagentRegistry.Poll poll = registry.poll(runId);
        ToolResult result = switch (poll.status()) {
            case PENDING -> ToolResult.success("still running");
            case COMPLETED -> poll.outcome().status().equals("success")
                    ? ToolResult.success(poll.outcome().result().isBlank()
                            ? "Subagent completed with no text result."
                            : poll.outcome().result())
                    : ToolResult.failure(
                            "Subagent failed (status=" + poll.outcome().status()
                                    + ", reason=" + poll.outcome().reason() + ")",
                            ToolErrorType.RUNTIME_ERROR);
            case CANCELLED -> ToolResult.failure(
                    "Subagent was cancelled.", ToolErrorType.RUNTIME_ERROR);
            case FAILED -> ToolResult.failure(
                    "Subagent raised an exception: " + poll.error().getMessage(),
                    ToolErrorType.RUNTIME_ERROR);
            case UNKNOWN -> ToolResult.failure(
                    "Unknown run_id: " + runId + ". Only background subagents can be queried.",
                    ToolErrorType.RUNTIME_ERROR);
        };
        return CompletableFuture.completedFuture(result);
    }
}
