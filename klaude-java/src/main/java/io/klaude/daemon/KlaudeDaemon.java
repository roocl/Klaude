package io.klaude.daemon;

import com.fasterxml.jackson.databind.JsonNode;
import io.klaude.agent.event.EventBus;
import io.klaude.protocol.Command;
import io.klaude.protocol.Event;
import io.klaude.protocol.AgentRunCommand;
import io.klaude.protocol.AgentRunResult;
import io.klaude.protocol.EventSubscribeCommand;
import io.klaude.protocol.EventSubscribeResult;
import io.klaude.protocol.PingCommand;
import io.klaude.protocol.PongResult;
import io.klaude.protocol.PermissionRespondCommand;
import io.klaude.protocol.PermissionRespondResult;
import io.klaude.protocol.ProtocolJson;
import io.klaude.observability.EventStore;
import io.klaude.observability.TraceStore;
import io.klaude.transport.EventBroadcaster;
import io.klaude.transport.InvalidParamsException;
import io.klaude.transport.NdjsonServer;
import io.klaude.transport.RpcDispatcher;
import io.klaude.transport.RpcHandlerException;
import io.klaude.transport.SubscriptionMatcher;
import io.klaude.transport.SubscriptionReceipt;
import io.klaude.tool.permission.PermissionManager;
import io.klaude.tool.permission.UserDecision;
import io.klaude.session.SessionManager;
import io.klaude.protocol.SessionCreateCommand;
import io.klaude.protocol.SessionCreateResult;
import io.klaude.protocol.SessionGetHistoryCommand;
import io.klaude.protocol.SessionGetHistoryResult;
import io.klaude.protocol.SessionCloseCommand;
import io.klaude.protocol.SessionCloseResult;
import io.klaude.protocol.SessionSendMessageCommand;
import io.klaude.protocol.SessionSendMessageResult;
import io.klaude.protocol.SessionCompactCommand;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.util.function.Function;

public final class KlaudeDaemon implements AutoCloseable {
    private static final String VERSION = "0.1.0";
    private final String host;
    private final Clock clock;
    private final Path runsRoot;
    private final TraceStore traceStore;
    private final EventBus eventBus;
    private final EventBroadcaster broadcaster;
    private final RpcDispatcher dispatcher;
    private final NdjsonServer server;
    private final PermissionManager permissions;
    private final Function<String, String> agentRuns;
    private final SessionManager sessions;
    private final Supplier<java.util.List<io.klaude.protocol.SkillInfo>> skills;
    private final java.util.function.Predicate<String> runCancellation;
    private volatile Instant startedAt;

    // 组合 daemon 的 transport、持久化路径、时间与 subscription ID 边界
    public KlaudeDaemon(
            String host,
            int port,
            Path runsRoot,
            Path tracePath,
            Clock clock,
            Supplier<String> subscriptionIds) {
        this(host, port, runsRoot, tracePath, clock, subscriptionIds, null, null, null);
    }

    // 组合 daemon 边界并注入 permission manager
    public KlaudeDaemon(
            String host,
            int port,
            Path runsRoot,
            Path tracePath,
            Clock clock,
            Supplier<String> subscriptionIds,
            PermissionManager permissions) {
        this(host, port, runsRoot, tracePath, clock, subscriptionIds, permissions, null, null);
    }

    // 组合 daemon 边界并注入 permission manager 与 agent run launcher
    public KlaudeDaemon(
            String host,
            int port,
            Path runsRoot,
            Path tracePath,
            Clock clock,
            Supplier<String> subscriptionIds,
            PermissionManager permissions,
            Function<String, String> agentRuns) {
        this(
                host,
                port,
                runsRoot,
                tracePath,
                clock,
                subscriptionIds,
                permissions,
                agentRuns,
                null);
    }

    // 组合 daemon 边界并注入 permission、agent run 与 session manager
    public KlaudeDaemon(
            String host,
            int port,
            Path runsRoot,
            Path tracePath,
            Clock clock,
            Supplier<String> subscriptionIds,
            PermissionManager permissions,
            Function<String, String> agentRuns,
            SessionManager sessions) {
        this(
                host,
                port,
                runsRoot,
                tracePath,
                clock,
                subscriptionIds,
                permissions,
                agentRuns,
                sessions,
                java.util.List::of);
    }

    // 组合 daemon 边界并注入 session 与 skill discovery
    public KlaudeDaemon(
            String host,
            int port,
            Path runsRoot,
            Path tracePath,
            Clock clock,
            Supplier<String> subscriptionIds,
            PermissionManager permissions,
            Function<String, String> agentRuns,
            SessionManager sessions,
            Supplier<java.util.List<io.klaude.protocol.SkillInfo>> skills) {
        this(
                host, port, runsRoot, tracePath, clock, subscriptionIds, permissions,
                agentRuns, sessions, skills, runId -> false);
    }

    // 组合 daemon 边界并注入完整发现与 run 取消能力
    public KlaudeDaemon(
            String host,
            int port,
            Path runsRoot,
            Path tracePath,
            Clock clock,
            Supplier<String> subscriptionIds,
            PermissionManager permissions,
            Function<String, String> agentRuns,
            SessionManager sessions,
            Supplier<java.util.List<io.klaude.protocol.SkillInfo>> skills,
            java.util.function.Predicate<String> runCancellation) {
        this.host = host;
        this.clock = clock;
        this.runsRoot = runsRoot.toAbsolutePath().normalize();
        this.traceStore = new TraceStore(tracePath);
        this.eventBus = new EventBus();
        this.broadcaster = new EventBroadcaster(subscriptionIds, clock, this::appendTrace);
        this.dispatcher = new RpcDispatcher(clock, this::appendTrace);
        this.permissions = permissions;
        this.agentRuns = agentRuns;
        this.sessions = sessions;
        this.skills = java.util.Objects.requireNonNull(skills, "skills");
        this.runCancellation = java.util.Objects.requireNonNull(runCancellation, "runCancellation");
        this.eventBus.subscribe(event -> {
            com.fasterxml.jackson.databind.node.ObjectNode wire =
                    ProtocolJson.mapper().valueToTree(event);
            return publish(wire).thenApply(ignored -> null);
        });
        registerHandlers();
        this.server = new NdjsonServer(host, port, 64 * 1024 * 1024, dispatcher);
    }

    // 注册 ping、event subscription 与 permission response handlers
    private void registerHandlers() {
        dispatcher.register("core.ping", (connection, params) -> {
            PingCommand command = (PingCommand) parseCommand("core.ping", params);
            Instant now = Instant.now(clock);
            PongResult result = new PongResult(
                    VERSION,
                    Duration.between(startedAt, now).toMillis(),
                    now.toString());
            return CompletableFuture.completedFuture(ProtocolJson.mapper().valueToTree(result));
        });
        dispatcher.register("event.subscribe", (connection, params) -> {
            EventSubscribeCommand command =
                    (EventSubscribeCommand) parseCommand("event.subscribe", params);
            SubscriptionReceipt receipt;
            if (command.replayFromRun() == null) {
                receipt = new SubscriptionReceipt(
                        broadcaster.subscribe(connection, command.topics(), command.scope()), 0);
            } else {
                try {
                    var replay = new EventStore(runsRoot
                            .resolve(command.replayFromRun())
                            .resolve("events.jsonl"))
                            .readAll();
                    receipt = broadcaster.subscribeWithReplay(
                            connection, command.topics(), command.scope(), replay);
                } catch (IOException error) {
                    throw new IllegalStateException("cannot replay events", error);
                }
            }
            EventSubscribeResult result = new EventSubscribeResult(
                    receipt.subscriptionId(), receipt.replayedCount());
            if (SubscriptionMatcher.matchesTopic("core.started", command.topics())
                    && SubscriptionMatcher.matchesScope(null, command.scope())) {
                var event = ProtocolJson.mapper().createObjectNode()
                        .put("type", "core.started")
                        .put("listen_addr", host + ":" + port())
                        .put("version", VERSION);
                return broadcaster.sendDirect(connection, receipt.subscriptionId(), event)
                        .thenApply(ignored -> ProtocolJson.mapper().valueToTree(result));
            }
            return CompletableFuture.completedFuture(ProtocolJson.mapper().valueToTree(result));
        });
        if (agentRuns != null) {
            dispatcher.register("agent.run", (connection, params) -> {
                AgentRunCommand command = (AgentRunCommand) parseCommand("agent.run", params);
                String runId = agentRuns.apply(command.goal());
                return CompletableFuture.completedFuture(ProtocolJson.mapper().valueToTree(
                        new AgentRunResult(runId)));
            });
            dispatcher.register("run.cancel", (connection, params) -> {
                var command = (io.klaude.protocol.RunCancelCommand) parseCommand(
                        "run.cancel", params);
                return CompletableFuture.completedFuture(ProtocolJson.mapper().valueToTree(
                        new io.klaude.protocol.RunCancelResult(
                                runCancellation.test(command.runId()))));
            });
        }
        if (sessions != null) {
            dispatcher.register("session.create", (connection, params) -> {
                SessionCreateCommand command =
                        (SessionCreateCommand) parseCommand("session.create", params);
                return mapSessionErrors(sessions.create(command.mode(), command.title()))
                        .thenApply(session -> ProtocolJson.mapper().valueToTree(
                                new SessionCreateResult(session.id(), session.status())));
            });
            dispatcher.register("session.get_history", (connection, params) -> {
                SessionGetHistoryCommand command = (SessionGetHistoryCommand) parseCommand(
                        "session.get_history", params);
                return mapSessionErrors(sessions.getHistory(command.sessionId()))
                        .thenApply(messages -> ProtocolJson.mapper().valueToTree(
                                new SessionGetHistoryResult(messages)));
            });
            dispatcher.register("session.list", (connection, params) -> {
                parseCommand("session.list", params);
                return sessions.listSessions().thenApply(items ->
                        ProtocolJson.mapper().valueToTree(
                                new io.klaude.protocol.SessionListResult(items.stream()
                                        .map(KlaudeDaemon::sessionInfo)
                                        .toList())));
            });
            dispatcher.register("session.close", (connection, params) -> {
                SessionCloseCommand command =
                        (SessionCloseCommand) parseCommand("session.close", params);
                return mapSessionErrors(sessions.close(command.sessionId()))
                        .thenApply(status -> ProtocolJson.mapper().valueToTree(
                                new SessionCloseResult(status)));
            });
            dispatcher.register("session.send_message", (connection, params) -> {
                SessionSendMessageCommand command = (SessionSendMessageCommand) parseCommand(
                        "session.send_message", params);
                return mapSessionErrors(sessions.sendMessage(
                                command.sessionId(), command.content()))
                        .thenApply(runId -> ProtocolJson.mapper().valueToTree(
                                new SessionSendMessageResult(runId)));
            });
            dispatcher.register("session.compact", (connection, params) -> {
                SessionCompactCommand command = (SessionCompactCommand) parseCommand(
                        "session.compact", params);
                return mapSessionErrors(sessions.compact(
                                command.sessionId(), command.focus()))
                        .thenApply(ProtocolJson.mapper()::valueToTree);
            });
        }
        dispatcher.register("skill.list", (connection, params) -> {
            parseCommand("skill.list", params);
            return CompletableFuture.completedFuture(ProtocolJson.mapper().valueToTree(
                    new io.klaude.protocol.SkillListResult(skills.get())));
        });
        if (permissions != null) {
            dispatcher.register("permission.respond", (connection, params) -> {
                PermissionRespondCommand command =
                        (PermissionRespondCommand) parseCommand("permission.respond", params);
                try {
                    permissions.respond(
                            command.toolUseId(),
                            UserDecision.fromWireValue(command.decision()));
                } catch (IllegalArgumentException error) {
                    throw new InvalidParamsException(error.getMessage(), error);
                }
                return CompletableFuture.completedFuture(ProtocolJson.mapper().valueToTree(
                        new PermissionRespondResult(Boolean.TRUE)));
            });
        }
    }

    // 将内部 session metadata 映射为稳定的公开列表项
    private static io.klaude.protocol.SessionInfo sessionInfo(io.klaude.session.Session session) {
        String lastRunId = session.runIds().isEmpty()
                ? ""
                : session.runIds().getLast();
        return new io.klaude.protocol.SessionInfo(
                session.id(),
                session.mode(),
                session.status(),
                session.title(),
                session.updatedAt(),
                lastRunId);
    }

    // 给裸 params 补入 method discriminator 并通过 sealed Command 入口校验
    private static Command parseCommand(
            String method, com.fasterxml.jackson.databind.node.ObjectNode params) {
        try {
            var commandParams = params.deepCopy();
            commandParams.put("type", method);
            return ProtocolJson.mapper().treeToValue(commandParams, Command.class);
        } catch (Exception error) {
            throw new InvalidParamsException(error.getMessage(), error);
        }
    }

    // 将 session 域异常转换为 transport 可识别的 JSON-RPC handler error
    private static <T> java.util.concurrent.CompletionStage<T> mapSessionErrors(
            java.util.concurrent.CompletionStage<T> completion) {
        return completion.exceptionallyCompose(error -> {
            Throwable cause = error;
            while ((cause instanceof java.util.concurrent.CompletionException
                            || cause instanceof java.util.concurrent.ExecutionException)
                    && cause.getCause() != null) {
                cause = cause.getCause();
            }
            if (cause instanceof io.klaude.session.SessionException sessionError) {
                return CompletableFuture.failedFuture(new RpcHandlerException(
                        sessionError.code(), sessionError.getMessage(), null));
            }
            return CompletableFuture.failedFuture(cause);
        });
    }

    // 尽力追加 IPC trace，监控失败不影响 daemon 请求路径
    private void appendTrace(com.fasterxml.jackson.databind.node.ObjectNode trace) {
        try {
            traceStore.append(trace);
        } catch (IOException ignored) {
            // Trace persistence is best effort.
        }
    }

    // 异步启动 Netty server 并记录 uptime 起点
    public CompletableFuture<Void> start() {
        startedAt = Instant.now(clock);
        return server.start();
    }

    // 返回 daemon 实际绑定的 TCP 端口
    public int port() {
        return server.port();
    }

    // 将实时事件先持久化到 run events.jsonl 再广播给匹配订阅
    public java.util.concurrent.CompletionStage<Integer> publish(
            com.fasterxml.jackson.databind.node.ObjectNode event) {
        String runId = event.hasNonNull("run_id") ? event.path("run_id").asText() : null;
        if (runId != null) {
            try {
                new EventStore(runsRoot.resolve(runId).resolve("events.jsonl")).append(event);
            } catch (IOException error) {
                return CompletableFuture.failedFuture(error);
            }
        }
        return broadcaster.publish(event);
    }

    // 通过顺序 EventBus 发布一个不可变协议事件
    public java.util.concurrent.CompletionStage<Void> publish(Event event) {
        return eventBus.publish(event);
    }

    // 关闭所有连接、handler、event loop 和监听 channel
    @Override
    public void close() {
        server.close();
    }
}
