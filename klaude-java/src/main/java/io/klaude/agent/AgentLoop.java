package io.klaude.agent;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.klaude.agent.event.EventBus;
import io.klaude.llm.LlmProvider;
import io.klaude.llm.LlmRequest;
import io.klaude.llm.LlmResponse;
import io.klaude.llm.LlmStopReason;
import io.klaude.llm.LlmToolCall;
import io.klaude.protocol.ProtocolJson;
import io.klaude.protocol.StepFinishedEvent;
import io.klaude.protocol.StepStartedEvent;
import io.klaude.tool.ToolRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public final class AgentLoop {
    private static final String SYSTEM_PROMPT =
            "You are a helpful AI assistant. Use the available tools to complete the user's goal. "
                    + "When the goal is fully achieved, respond with a final answer and do not call any more tools.";
    private final LlmProvider provider;
    private final ToolRegistry tools;
    private final AgentToolExecutor toolExecutor;
    private final EventBus events;
    private final Clock clock;
    private final AgentCompactor compactor;
    private final double compactThreshold;

    // 初始化 provider、工具注册表、事件总线和时间边界
    public AgentLoop(
            LlmProvider provider,
            ToolRegistry tools,
            EventBus events,
            Clock clock) {
        this(
                provider,
                tools,
                call -> CompletableFuture.completedFuture(io.klaude.tool.ToolResult.failure(
                        "unknown tool: " + call.name(),
                        io.klaude.tool.ToolErrorType.RUNTIME_ERROR)),
                events,
                clock);
    }

    // 初始化 provider、registry、工具执行器、事件总线和时间边界
    public AgentLoop(
            LlmProvider provider,
            ToolRegistry tools,
            AgentToolExecutor toolExecutor,
            EventBus events,
            Clock clock) {
        this(provider, tools, toolExecutor, events, clock, null, 0);
    }

    // 初始化包含可选 compactor 与 usage 阈值的完整 agent loop
    public AgentLoop(
            LlmProvider provider,
            ToolRegistry tools,
            AgentToolExecutor toolExecutor,
            EventBus events,
            Clock clock,
            AgentCompactor compactor,
            double compactThreshold) {
        if (compactThreshold < 0 || compactThreshold > 1) {
            throw new IllegalArgumentException("compactThreshold must be between 0 and 1");
        }
        this.provider = java.util.Objects.requireNonNull(provider, "provider");
        this.tools = java.util.Objects.requireNonNull(tools, "tools");
        this.toolExecutor = java.util.Objects.requireNonNull(toolExecutor, "toolExecutor");
        this.events = java.util.Objects.requireNonNull(events, "events");
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
        this.compactor = compactor;
        this.compactThreshold = compactThreshold;
    }

    // 执行 agent steps 直到 context 进入终止状态
    public CompletionStage<Void> run(ExecutionContext context) {
        return runStep(context);
    }

    // 执行一步 plan-act-observe 并在需要时继续下一步
    private CompletionStage<Void> runStep(ExecutionContext context) {
        int step = context.startStep();
        var started = new StepStartedEvent(
                context.runId(), step, Instant.now(clock).toString());
        return events.publish(started)
                .thenCompose(ignored -> callProvider(context, step))
                .thenCompose(outcome -> {
                    if (outcome.error() != null) {
                        Throwable error = unwrap(outcome.error());
                        if (error instanceof java.util.concurrent.CancellationException) {
                            context.markFailed("cancelled");
                            return CompletableFuture.failedFuture(error);
                        }
                        context.markFailed("llm_error");
                        return CompletableFuture.completedFuture(null);
                    }
                    return handleResponse(context, step, outcome.response());
                })
                .thenCompose(ignored -> context.isDone()
                        ? CompletableFuture.completedFuture(null)
                        : runStep(context));
    }

    // 将 provider 的同步或异步结果隔离为可分类 outcome
    private CompletionStage<ProviderOutcome> callProvider(
            ExecutionContext context, int step) {
        var completion = new CompletableFuture<ProviderOutcome>();
        try {
            provider.chat(
                            new LlmRequest(
                                    context.runId(),
                                    step,
                                    context.messages(),
                                    toolSchemas(),
                                    context.systemPrompt(SYSTEM_PROMPT)),
                            events::publish)
                    .whenComplete((response, error) ->
                            completion.complete(new ProviderOutcome(response, error)));
        } catch (Throwable error) {
            completion.complete(new ProviderOutcome(null, error));
        }
        return completion;
    }

    // 处理一个成功 provider 响应并发布 step finished
    private CompletionStage<Void> handleResponse(
            ExecutionContext context, int step, LlmResponse response) {
        context.addAssistantMessage(assistantBlocks(response));
        if (response.stopReason() == LlmStopReason.END_TURN) {
            context.markSuccess(response.text());
        }
        CompletionStage<Void> action = response.stopReason() == LlmStopReason.TOOL_USE
                ? executeTools(response.toolCalls(), context)
                : response.stopReason() == LlmStopReason.MAX_TOKENS
                        ? balanceTruncatedTools(response.toolCalls(), context)
                        : CompletableFuture.completedFuture(null);
        return action.thenCompose(ignored -> {
            if (response.stopReason() != LlmStopReason.END_TURN
                    && context.step() >= context.maxSteps()) {
                context.markFailed("exceeded_max_steps");
            }
            if (!context.isDone()
                    && response.stopReason() == LlmStopReason.TOOL_USE
                    && compactor != null
                    && compactThreshold > 0
                    && response.usage() != null
                    && response.usage().contextPercentage() >= compactThreshold) {
                return compactor.compact(context);
            }
            return CompletableFuture.completedFuture(null);
        }).thenCompose(ignored -> events.publish(new StepFinishedEvent(
                context.runId(), step, Instant.now(clock).toString())));
    }

    // 解开 completion wrapper 以保留 provider 原始错误分类
    private static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while ((current instanceof java.util.concurrent.CompletionException
                        || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    // 按 provider 返回顺序串行执行工具并追加结果
    private CompletionStage<Void> executeTools(
            List<LlmToolCall> calls, ExecutionContext context) {
        CompletionStage<Void> completion = CompletableFuture.completedFuture(null);
        for (LlmToolCall call : calls) {
            completion = completion.thenCompose(ignored -> toolExecutor.invoke(call)
                    .thenAccept(result -> context.addToolResult(
                            call.id(), result.content(), result.isError())));
        }
        return completion;
    }

    // 为 max-token 中断的工具调用生成配对错误结果
    private static CompletionStage<Void> balanceTruncatedTools(
            List<LlmToolCall> calls, ExecutionContext context) {
        String message = "Error: output token limit reached before this tool call could be completed. "
                + "Please break the task into smaller steps and try again.";
        for (LlmToolCall call : calls) {
            context.addToolResult(call.id(), message, true);
        }
        return CompletableFuture.completedFuture(null);
    }

    // 按 thinking、text、tool_use 顺序构造 assistant blocks
    private static com.fasterxml.jackson.databind.node.ArrayNode assistantBlocks(
            LlmResponse response) {
        var blocks = ProtocolJson.mapper().createArrayNode();
        response.thinkingBlocks().forEach(blocks::add);
        if (!response.text().isEmpty()) {
            blocks.add(ProtocolJson.mapper().createObjectNode()
                    .put("type", "text")
                    .put("text", response.text()));
        }
        for (LlmToolCall call : response.toolCalls()) {
            ObjectNode block = ProtocolJson.mapper().createObjectNode();
            block.put("type", "tool_use");
            block.put("id", call.id());
            block.put("name", call.name());
            block.set("input", call.input());
            blocks.add(block);
        }
        return blocks;
    }

    private record ProviderOutcome(LlmResponse response, Throwable error) {}

    // 将 registry definitions 转为 provider 工具 schema JSON
    private List<ObjectNode> toolSchemas() {
        return tools.definitions().stream().map(definition -> {
            ObjectNode schema = ProtocolJson.mapper().createObjectNode();
            schema.put("name", definition.name());
            schema.put("description", definition.description());
            schema.set("input_schema", definition.inputSchema());
            return schema;
        }).toList();
    }
}
