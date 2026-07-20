package io.klaude.tool;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import io.klaude.protocol.ToolCallFailedEvent;
import io.klaude.protocol.ToolCallStartedEvent;
import io.klaude.protocol.ToolCallFinishedEvent;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class ToolInvoker implements AutoCloseable {
    private final ToolRegistry registry;
    private final PermissionGateway permissions;
    private final ToolEventSink events;
    private final Clock clock;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("tool-timeout").factory());
    private final JsonSchemaFactory schemas =
            JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);

    // 初始化 registry、permission、event 和时间边界
    public ToolInvoker(
            ToolRegistry registry,
            PermissionGateway permissions,
            ToolEventSink events,
            Clock clock) {
        this.registry = java.util.Objects.requireNonNull(registry, "registry");
        this.permissions = java.util.Objects.requireNonNull(permissions, "permissions");
        this.events = java.util.Objects.requireNonNull(events, "events");
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
    }

    // 校验、授权并异步执行一个工具调用
    public CompletionStage<ToolResult> invoke(
            ToolContext context,
            String toolName,
            ObjectNode params,
            Duration timeout) {
        Instant started = Instant.now(clock);
        ToolCallStartedEvent event = new ToolCallStartedEvent(
                context.runId(),
                context.toolUseId(),
                toolName,
                params.deepCopy(),
                started.toString());
        return events.emit(event).thenCompose(ignored ->
                invokeAfterStarted(context, toolName, params.deepCopy(), timeout, started));
    }

    // 在 started event 完成后执行 lookup 和 schema-first 流程
    private CompletionStage<ToolResult> invokeAfterStarted(
            ToolContext context,
            String toolName,
            ObjectNode params,
            Duration timeout,
            Instant started) {
        var found = registry.get(toolName);
        if (found.isEmpty()) {
            return fail(
                    context,
                    toolName,
                    ToolErrorType.RUNTIME_ERROR,
                    "unknown tool: " + toolName,
                    started);
        }
        Tool tool = found.orElseThrow();
        var errors = schemas.getSchema(tool.inputSchema()).validate(params);
        if (!errors.isEmpty()) {
            String message = errors.stream()
                    .map(Object::toString)
                    .sorted()
                    .collect(java.util.stream.Collectors.joining("; "));
            return fail(context, toolName, ToolErrorType.SCHEMA_ERROR, message, started);
        }
        return permissions.check(context, tool, params).thenCompose(outcome -> {
            if (!outcome.allowed()) {
                return fail(
                        context,
                        toolName,
                        ToolErrorType.PERMISSION_DENIED,
                        "Permission denied by user.",
                        started);
            }
            return execute(context, tool, params, timeout, started);
        });
    }

    // 在 virtual thread 上启动工具主体并将同步异常转为失败结果
    private CompletionStage<ToolResult> execute(
            ToolContext context,
            Tool tool,
            ObjectNode params,
            Duration timeout,
            Instant started) {
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        CompletableFuture<ToolResult> completion = new CompletableFuture<>();
        AtomicBoolean settled = new AtomicBoolean();
        AtomicReference<Future<?>> taskReference = new AtomicReference<>();
        var timeoutTask = scheduler.schedule(() -> {
            if (!settled.compareAndSet(false, true)) {
                return;
            }
            Future<?> task = taskReference.get();
            if (task != null) {
                task.cancel(true);
            }
            fail(
                    context,
                    tool.name(),
                    ToolErrorType.TIMEOUT,
                    "tool timed out after " + timeout.toMillis() + "ms",
                    started).whenComplete((failure, eventError) ->
                    complete(completion, failure, eventError));
        }, timeout.toNanos(), TimeUnit.NANOSECONDS);
        Future<?> task = executor.submit(() -> {
            try {
                tool.execute(context, params).whenComplete((result, error) -> {
                    if (!settled.compareAndSet(false, true)) {
                        return;
                    }
                    timeoutTask.cancel(false);
                    if (error == null && !result.isError()) {
                        finish(context, tool.name(), result, started).whenComplete(
                                (finished, eventError) -> complete(completion, finished, eventError));
                    } else if (error == null) {
                        fail(context, tool.name(), result.errorType(), result.content(), started)
                                .whenComplete((failure, eventError) ->
                                        complete(completion, failure, eventError));
                    } else {
                        fail(context, tool.name(), ToolErrorType.RUNTIME_ERROR,
                                error.getMessage(), started).whenComplete((failure, ignored) ->
                                completion.complete(failure));
                    }
                });
            } catch (Throwable error) {
                if (!settled.compareAndSet(false, true)) {
                    return;
                }
                timeoutTask.cancel(false);
                fail(context, tool.name(), ToolErrorType.RUNTIME_ERROR,
                        error.getMessage(), started).whenComplete((failure, ignored) ->
                        completion.complete(failure));
            }
        });
        taskReference.set(task);
        if (settled.get()) {
            task.cancel(true);
        }
        return completion;
    }

    // 发布 finished event 并返回原成功结果
    private CompletionStage<ToolResult> finish(
            ToolContext context,
            String toolName,
            ToolResult result,
            Instant started) {
        int elapsed = Math.toIntExact(Duration.between(started, Instant.now(clock)).toMillis());
        ToolCallFinishedEvent event = new ToolCallFinishedEvent(
                context.runId(),
                context.toolUseId(),
                toolName,
                elapsed,
                result.content(),
                Instant.now(clock).toString());
        return events.emit(event).thenApply(ignored -> result);
    }

    // 将异步 event 结果转发到 invocation completion
    private static void complete(
            CompletableFuture<ToolResult> completion,
            ToolResult result,
            Throwable error) {
        if (error == null) {
            completion.complete(result);
        } else {
            completion.completeExceptionally(error);
        }
    }

    // 发布 failed event 并返回对应错误 ToolResult
    private CompletionStage<ToolResult> fail(
            ToolContext context,
            String toolName,
            ToolErrorType errorType,
            String message,
            Instant started) {
        int elapsed = Math.toIntExact(Duration.between(started, Instant.now(clock)).toMillis());
        ToolCallFailedEvent event = new ToolCallFailedEvent(
                context.runId(),
                context.toolUseId(),
                toolName,
                errorType.wireValue(),
                message == null ? "" : message,
                elapsed,
                1,
                Instant.now(clock).toString());
        return events.emit(event).thenApply(ignored ->
                ToolResult.failure(event.errorMessage(), errorType));
    }

    // 关闭 virtual-thread executor 并等待已提交调用结束
    @Override
    public void close() {
        scheduler.shutdownNow();
        executor.close();
    }
}
