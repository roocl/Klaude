package io.klaude.extension.subagent;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.klaude.agent.RunOutcome;
import io.klaude.agent.event.EventBus;
import io.klaude.extension.profile.AgentProfile;
import io.klaude.protocol.ProtocolJson;
import io.klaude.protocol.SubagentFinishedEvent;
import io.klaude.protocol.SubagentStartedEvent;
import io.klaude.tool.Tool;
import io.klaude.tool.ToolContext;
import io.klaude.tool.ToolErrorType;
import io.klaude.tool.ToolResult;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;
import java.util.function.Supplier;

public final class SpawnAgentTool implements Tool {
    private final SubagentRunner runner;
    private final EventBus events;
    private final BackgroundSubagentRegistry backgrounds;
    private final Supplier<String> runIds;
    private final Function<String, Optional<AgentProfile>> profiles;
    private final Clock clock;
    private final int depth;

    // 初始化 child runner、事件、registry、身份、profile 与深度边界
    public SpawnAgentTool(
            SubagentRunner runner,
            EventBus events,
            BackgroundSubagentRegistry backgrounds,
            Supplier<String> runIds,
            Function<String, Optional<AgentProfile>> profiles,
            Clock clock,
            int depth) {
        this.runner = java.util.Objects.requireNonNull(runner, "runner");
        this.events = java.util.Objects.requireNonNull(events, "events");
        this.backgrounds = java.util.Objects.requireNonNull(backgrounds, "backgrounds");
        this.runIds = java.util.Objects.requireNonNull(runIds, "runIds");
        this.profiles = java.util.Objects.requireNonNull(profiles, "profiles");
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
        this.depth = depth;
    }

    // 返回 spawn_agent 工具名
    @Override
    public String name() {
        return "spawn_agent";
    }

    // 返回隔离 child agent 行为描述
    @Override
    public String description() {
        return "Spawn an isolated sub-agent for a self-contained task.";
    }

    // 返回 spawn agent 参数 JSON Schema
    @Override
    public ObjectNode inputSchema() {
        ObjectNode schema = ProtocolJson.mapper().createObjectNode().put("type", "object");
        ObjectNode properties = ProtocolJson.mapper().createObjectNode();
        properties.set("description", ProtocolJson.mapper().createObjectNode().put("type", "string"));
        properties.set("prompt", ProtocolJson.mapper().createObjectNode().put("type", "string"));
        properties.set("run_in_background", ProtocolJson.mapper().createObjectNode().put("type", "boolean"));
        properties.set("subagent_type", ProtocolJson.mapper().createObjectNode().put("type", "string"));
        schema.set("properties", properties);
        schema.set("required", ProtocolJson.mapper().createArrayNode()
                .add("description").add("prompt"));
        return schema;
    }

    // 派生前台 child agent 并将其生命周期桥接到 parent bus
    @Override
    public CompletionStage<ToolResult> execute(ToolContext context, ObjectNode params) {
        if (depth >= 2) {
            return CompletableFuture.completedFuture(ToolResult.failure(
                    "Subagent nesting limit (2) reached; cannot spawn further subagents.",
                    ToolErrorType.RUNTIME_ERROR));
        }
        if (params.path("run_in_background").asBoolean(false)) {
            return startBackground(context, params);
        }
        String runId = java.util.Objects.requireNonNull(runIds.get(), "run ID");
        String description = params.path("description").asText();
        String prompt = params.path("prompt").asText();
        String profileName = params.path("subagent_type").asText("");
        AgentProfile profile = profileName.isBlank()
                ? null
                : profiles.apply(profileName).orElse(null);
        var request = new SubagentRunRequest(
                runId, context.runId(), context.sessionId(), prompt, profile, depth + 1);
        return events.publish(new SubagentStartedEvent(
                        runId, context.runId(), description, Instant.now(clock).toString()))
                .thenCompose(ignored -> runner.run(request))
                .thenCompose(outcome -> finish(context.runId(), outcome));
    }

    // 启动后台 child、注册完成链并立即返回 run ID
    private CompletionStage<ToolResult> startBackground(
            ToolContext context, ObjectNode params) {
        if (depth >= 2) {
            return CompletableFuture.completedFuture(ToolResult.failure(
                    "Subagent nesting limit (2) reached; cannot spawn further subagents.",
                    ToolErrorType.RUNTIME_ERROR));
        }
        String runId = java.util.Objects.requireNonNull(runIds.get(), "run ID");
        String description = params.path("description").asText();
        String profileName = params.path("subagent_type").asText("");
        AgentProfile profile = profileName.isBlank()
                ? null
                : profiles.apply(profileName).orElse(null);
        var request = new SubagentRunRequest(
                runId,
                context.runId(),
                context.sessionId(),
                params.path("prompt").asText(),
                profile,
                depth + 1);
        return events.publish(new SubagentStartedEvent(
                        runId, context.runId(), description, Instant.now(clock).toString()))
                .thenApply(ignored -> {
                    CompletableFuture<RunOutcome> completion = new CompletableFuture<>();
                    CompletableFuture<RunOutcome> childCompletion;
                    try {
                        childCompletion = runner.run(request)
                                .toCompletableFuture();
                        childCompletion
                                .thenCompose(outcome -> publishFinished(context.runId(), outcome)
                                        .thenApply(finished -> outcome))
                                .whenComplete((outcome, error) -> {
                                    if (error == null) {
                                        completion.complete(outcome);
                                    } else {
                                        completion.completeExceptionally(error);
                                    }
                                });
                    } catch (Throwable error) {
                        completion.completeExceptionally(error);
                        childCompletion = CompletableFuture.failedFuture(error);
                    }
                    backgrounds.register(runId, completion, childCompletion);
                    return ToolResult.success(
                            "Subagent started in background. run_id=" + runId
                                    + ". Use agent_result to retrieve result.");
                });
    }

    // 发布 child finished 并将 outcome 转为工具结果
    private CompletionStage<ToolResult> finish(String parentRunId, RunOutcome outcome) {
        return publishFinished(parentRunId, outcome)
                .thenApply(ignored -> outcome.status().equals("success")
                        ? ToolResult.success(outcome.result().isBlank()
                                ? "Subagent completed with no text output."
                                : outcome.result())
                        : ToolResult.failure(
                                outcome.result().isBlank()
                                        ? "Subagent failed (status=" + outcome.status()
                                                + ", reason=" + outcome.reason() + ")"
                                        : outcome.result(),
                                ToolErrorType.RUNTIME_ERROR));
    }

    // 发布一个 parent-linked child finished 事件
    private CompletionStage<Void> publishFinished(
            String parentRunId, RunOutcome outcome) {
        return events.publish(new SubagentFinishedEvent(
                outcome.runId(),
                parentRunId,
                outcome.status(),
                Instant.now(clock).toString()));
    }
}
