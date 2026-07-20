package io.klaude.agent;

import io.klaude.agent.event.EventBus;
import io.klaude.llm.LlmProvider;
import io.klaude.protocol.RunFinishedEvent;
import io.klaude.protocol.RunStartedEvent;
import io.klaude.tool.ToolRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

public final class AgentRunner {
    private final LlmProvider provider;
    private final ToolRegistry tools;
    private final AgentToolExecutor toolExecutor;
    private final EventBus events;
    private final Clock clock;
    private final Supplier<String> runIds;
    private final int maxSteps;
    private final AgentCompactor compactor;
    private final double compactThreshold;

    // 初始化一次 run 所需的可测边界
    public AgentRunner(
            LlmProvider provider,
            ToolRegistry tools,
            AgentToolExecutor toolExecutor,
            EventBus events,
            Clock clock,
            Supplier<String> runIds,
            int maxSteps) {
        this(provider, tools, toolExecutor, events, clock, runIds, maxSteps, null, 0);
    }

    // 初始化包含可选自动 compactor 的完整 run 边界
    public AgentRunner(
            LlmProvider provider,
            ToolRegistry tools,
            AgentToolExecutor toolExecutor,
            EventBus events,
            Clock clock,
            Supplier<String> runIds,
            int maxSteps,
            AgentCompactor compactor,
            double compactThreshold) {
        this.provider = java.util.Objects.requireNonNull(provider, "provider");
        this.tools = java.util.Objects.requireNonNull(tools, "tools");
        this.toolExecutor = java.util.Objects.requireNonNull(toolExecutor, "toolExecutor");
        this.events = java.util.Objects.requireNonNull(events, "events");
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
        this.runIds = java.util.Objects.requireNonNull(runIds, "runIds");
        if (maxSteps < 1) {
            throw new IllegalArgumentException("maxSteps must be positive");
        }
        if (compactThreshold < 0 || compactThreshold > 1) {
            throw new IllegalArgumentException("compactThreshold must be between 0 and 1");
        }
        this.maxSteps = maxSteps;
        this.compactor = compactor;
        this.compactThreshold = compactThreshold;
    }

    // 执行一次 agent run 并发布开始与结束生命周期
    public CompletionStage<RunOutcome> run(String goal) {
        String runId = java.util.Objects.requireNonNull(runIds.get(), "run ID");
        var context = new ExecutionContext(runId, goal, maxSteps);
        return runContext(goal, context);
    }

    // 使用固定身份与预填 context 执行 run 并返回本轮新增消息
    public CompletionStage<AgentRunCapture> runCaptured(AgentRunRequest request) {
        java.util.Objects.requireNonNull(request, "request");
        var context = new ExecutionContext(
                request.runId(),
                request.goal(),
                maxSteps,
                request.prefillMessages(),
                request.sessionNotes(),
                request.globalContext(),
                request.projectContext(),
                request.systemPromptOverride());
        int prefillSize = request.prefillMessages().size();
        return runContext(request.goal(), context).thenApply(outcome -> {
            var messages = context.messages();
            int firstNewMessage = Math.min(prefillSize, messages.size());
            return new AgentRunCapture(
                    outcome, messages.subList(firstNewMessage, messages.size()));
        });
    }

    // 执行共享 run lifecycle 并返回最终 outcome
    private CompletionStage<RunOutcome> runContext(
            String goal, ExecutionContext context) {
        String runId = context.runId();
        var started = new RunStartedEvent(runId, goal, Instant.now(clock).toString());
        return events.publish(started)
                .thenCompose(ignored -> new AgentLoop(
                        provider,
                        tools,
                        toolExecutor,
                        events,
                        clock,
                        compactor,
                        compactThreshold).run(context))
                .handle((ignored, error) -> new LoopOutcome(error))
                .thenCompose(loopOutcome -> finish(context, loopOutcome.error()));
    }

    // 发布 run finished 后返回 outcome 或恢复 loop 异常
    private CompletionStage<RunOutcome> finish(
            ExecutionContext context, Throwable error) {
        if (error != null && !context.isDone()) {
            context.markFailed("llm_error");
        }
        var finished = new RunFinishedEvent(
                context.runId(),
                context.status(),
                context.reason(),
                context.step(),
                Instant.now(clock).toString());
        return events.publish(finished).thenCompose(ignored -> {
            if (error != null) {
                return CompletableFuture.failedFuture(error);
            }
            return CompletableFuture.completedFuture(new RunOutcome(
                    context.runId(),
                    context.status(),
                    context.result(),
                    context.reason(),
                    context.step()));
        });
    }

    private record LoopOutcome(Throwable error) {}
}
