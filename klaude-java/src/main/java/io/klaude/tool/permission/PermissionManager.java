package io.klaude.tool.permission;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.klaude.protocol.PermissionDeniedEvent;
import io.klaude.protocol.PermissionGrantedEvent;
import io.klaude.protocol.PermissionRequestedEvent;
import io.klaude.tool.PermissionGateway;
import io.klaude.tool.PermissionOutcome;
import io.klaude.tool.Tool;
import io.klaude.tool.ToolContext;
import io.klaude.tool.ToolEventSink;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

public final class PermissionManager implements PermissionGateway, AutoCloseable {
    private static final Pattern OUTSIDE_CWD = Pattern.compile(
            "(^|\\s)(/[^\\s]|~|\\.\\.([/\\\\]|$|\\s))|\\$\\{?(HOME|PWD)\\b|(^|\\s|;|&&|\\|\\|)cd(\\s|$)");
    private static final Pattern WINDOWS_ABSOLUTE = Pattern.compile(
            "(^|\\s)([A-Za-z]:[\\\\/]|\\\\\\\\)");
    private final Map<String, ToolPolicy> policies;
    private final PermissionPolicyStore store;
    private final ToolEventSink events;
    private final Clock clock;
    private final Duration timeout;
    private final ConcurrentHashMap<String, PolicyDecision> persistent = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Pending> pending = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("permission-timeout").factory());

    // 加载持久策略并初始化 pending decision 生命周期
    public PermissionManager(
            Map<String, ToolPolicy> policies,
            PermissionPolicyStore store,
            ToolEventSink events,
            Clock clock,
            Duration timeout) throws IOException {
        this.policies = Map.copyOf(policies);
        this.store = store;
        this.events = events;
        this.clock = clock;
        this.timeout = timeout;
        this.persistent.putAll(store.load());
    }

    // 评估静态/持久策略或创建一个等待用户响应的 permission request
    @Override
    public java.util.concurrent.CompletionStage<PermissionOutcome> check(
            ToolContext context, Tool tool, ObjectNode params) {
        PermissionAction action = evaluate(tool.name(), params);
        if (action == PermissionAction.ALLOW) {
            return CompletableFuture.completedFuture(PermissionOutcome.allow("auto_allow"));
        }
        if (action == PermissionAction.DENY) {
            return CompletableFuture.completedFuture(PermissionOutcome.deny("auto_deny"));
        }
        CompletableFuture<PermissionOutcome> result = new CompletableFuture<>();
        Pending request = new Pending(context, tool.name(), params.deepCopy(), result);
        Pending previous = pending.putIfAbsent(context.toolUseId(), request);
        if (previous != null) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("duplicate pending tool_use_id: " + context.toolUseId()));
        }
        ScheduledFuture<?> timeoutTask = timeout.isZero()
                ? null
                : scheduler.schedule(
                        () -> timeout(context.toolUseId()),
                        timeout.toNanos(),
                        TimeUnit.NANOSECONDS);
        request.timeoutTask(timeoutTask);
        PermissionRequestedEvent event = new PermissionRequestedEvent(
                context.runId(),
                context.toolUseId(),
                tool.name(),
                params.deepCopy(),
                preview(tool.name(), params),
                context.sessionId(),
                Instant.now(clock).toString());
        events.emit(event).whenComplete((ignored, error) -> {
            if (error != null && pending.remove(context.toolUseId(), request)) {
                request.cancelTimeout();
                result.completeExceptionally(error);
            }
        });
        return result;
    }

    // 用一次性或永久用户 decision 解决一个 pending request
    public boolean respond(String toolUseId, UserDecision decision) {
        Pending request = pending.remove(toolUseId);
        if (request == null) {
            return false;
        }
        request.cancelTimeout();
        PolicyDecision persistentDecision = decision.persistentDecision();
        if (persistentDecision != null) {
            persistent.put(request.toolName(), persistentDecision);
            try {
                store.save(Map.copyOf(persistent));
            } catch (IOException error) {
                persistent.remove(request.toolName(), persistentDecision);
                request.result().completeExceptionally(error);
                return true;
            }
        }
        emitDecision(request, decision.wireValue(), decision.allowed())
                .whenComplete((ignored, error) -> {
                    if (error == null) {
                        request.result().complete(new PermissionOutcome(
                                decision.allowed(), decision.wireValue()));
                    } else {
                        request.result().completeExceptionally(error);
                    }
                });
        return true;
    }

    // 取消指定 session 的全部 pending permission requests
    public void cancelSession(String sessionId) {
        for (var entry : pending.entrySet()) {
            Pending request = entry.getValue();
            if (request.context().sessionId().equals(sessionId)
                    && pending.remove(entry.getKey(), request)) {
                request.cancelTimeout();
                emitDecision(request, "deny_once", false).whenComplete((ignored, error) ->
                        request.result().complete(PermissionOutcome.deny("deny_once")));
            }
        }
    }

    // 按 deny、outside-cwd、持久决策、allow 和默认动作顺序评估策略
    public PermissionAction evaluate(String toolName, ObjectNode params) {
        ToolPolicy policy = policies.getOrDefault(toolName, ToolPolicy.ask());
        String command = toolName.equals("bash") ? params.path("command").asText("") : "";
        if (matches(policy.denyPatterns(), command)) {
            return PermissionAction.DENY;
        }
        if (!command.isEmpty()
                && (OUTSIDE_CWD.matcher(command).find()
                        || WINDOWS_ABSOLUTE.matcher(command).find())) {
            return PermissionAction.ASK;
        }
        PolicyDecision saved = persistent.get(toolName);
        if (saved != null) {
            return saved == PolicyDecision.ALLOW ? PermissionAction.ALLOW : PermissionAction.DENY;
        }
        if (matches(policy.allowPatterns(), command)) {
            return PermissionAction.ALLOW;
        }
        return policy.defaultAction();
    }

    // 检查命令是否命中任一 regex pattern
    private static boolean matches(java.util.List<String> patterns, String command) {
        return !command.isEmpty()
                && patterns.stream().anyMatch(pattern -> Pattern.compile(pattern).matcher(command).find());
    }

    // 生成 permission request 的简短参数预览
    private static String preview(String toolName, ObjectNode params) {
        String key = switch (toolName) {
            case "bash" -> "command";
            case "read_file", "write_file", "list_dir" -> "path";
            case "note_save" -> "content";
            default -> null;
        };
        String value = key == null ? params.toString() : key + "=" + params.path(key).asText();
        return value.length() <= 60 ? value : value.substring(0, 60) + "...";
    }

    // 处理审批 timeout 并清理 pending entry
    private void timeout(String toolUseId) {
        Pending request = pending.remove(toolUseId);
        if (request == null) {
            return;
        }
        emitDecision(request, "timeout", false).whenComplete((ignored, error) ->
                request.result().complete(PermissionOutcome.deny("timeout")));
    }

    // 发布 granted 或 denied event
    private java.util.concurrent.CompletionStage<Void> emitDecision(
            Pending request, String decision, boolean allowed) {
        Instant now = Instant.now(clock);
        if (allowed) {
            return events.emit(new PermissionGrantedEvent(
                    request.context().runId(),
                    request.context().toolUseId(),
                    decision,
                    now.toString()));
        }
        return events.emit(new PermissionDeniedEvent(
                request.context().runId(),
                request.context().toolUseId(),
                decision,
                now.toString()));
    }

    // 取消 pending approvals 并关闭 timeout scheduler
    @Override
    public void close() {
        for (Pending request : pending.values()) {
            request.result().complete(PermissionOutcome.deny("shutdown"));
            request.cancelTimeout();
        }
        pending.clear();
        scheduler.shutdownNow();
    }

    private static final class Pending {
        private final ToolContext context;
        private final String toolName;
        private final ObjectNode params;
        private final CompletableFuture<PermissionOutcome> result;
        private volatile ScheduledFuture<?> timeoutTask;

        // 保存一个等待用户 decision 的 permission request
        private Pending(
                ToolContext context,
                String toolName,
                ObjectNode params,
                CompletableFuture<PermissionOutcome> result) {
            this.context = context;
            this.toolName = toolName;
            this.params = params;
            this.result = result;
        }

        // 返回工具上下文
        private ToolContext context() {
            return context;
        }

        // 返回工具名
        private String toolName() {
            return toolName;
        }

        // 返回 pending result future
        private CompletableFuture<PermissionOutcome> result() {
            return result;
        }

        // 保存 timeout task
        private void timeoutTask(ScheduledFuture<?> value) {
            timeoutTask = value;
        }

        // 取消已保存 timeout task
        private void cancelTimeout() {
            ScheduledFuture<?> task = timeoutTask;
            if (task != null) {
                task.cancel(false);
            }
        }
    }
}
