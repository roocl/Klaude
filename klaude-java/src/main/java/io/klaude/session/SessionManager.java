package io.klaude.session;

import io.klaude.protocol.SessionCreatedEvent;
import io.klaude.protocol.SessionMessageReceivedEvent;
import io.klaude.protocol.SessionMode;
import io.klaude.protocol.SessionStatus;
import io.klaude.protocol.SessionWaitingForInputEvent;
import java.time.Clock;
import java.time.Instant;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public final class SessionManager {
    public static final int SESSION_NOT_FOUND = -32010;
    public static final int SESSION_CLOSED = -32011;
    public static final int SESSION_BUSY = -32012;
    public static final int COMPACTION_UNAVAILABLE = -32020;
    public static final int COMPACTION_FAILED = -32021;
    private final SessionStore store;
    private final Supplier<String> sessionIds;
    private final Supplier<String> runIds;
    private final Clock clock;
    private final SessionEventSink events;
    private final SessionRunExecutor runs;
    private final ConversationSummarizer summarizer;
    private final SessionPromptResolver promptResolver;
    private final ConcurrentHashMap<String, AtomicBoolean> busy = new ConcurrentHashMap<>();

    // 初始化 session persistence、身份、时间与事件边界
    public SessionManager(
            SessionStore store,
            Supplier<String> sessionIds,
            Clock clock,
            SessionEventSink events) {
        this(
                store,
                sessionIds,
                () -> java.util.UUID.randomUUID().toString().replace("-", ""),
                clock,
                events,
                request -> CompletableFuture.failedFuture(
                        new IllegalStateException("session run executor is not configured")),
                null,
                SessionPrompt::unchanged);
    }

    // 初始化包含 run identity 与执行边界的完整 session manager
    public SessionManager(
            SessionStore store,
            Supplier<String> sessionIds,
            Supplier<String> runIds,
            Clock clock,
            SessionEventSink events,
            SessionRunExecutor runs) {
        this(store, sessionIds, runIds, clock, events, runs, null, SessionPrompt::unchanged);
    }

    // 初始化包含 run 与 conversation summary 边界的完整 session manager
    public SessionManager(
            SessionStore store,
            Supplier<String> sessionIds,
            Supplier<String> runIds,
            Clock clock,
            SessionEventSink events,
            SessionRunExecutor runs,
            ConversationSummarizer summarizer) {
        this(store, sessionIds, runIds, clock, events, runs, summarizer, SessionPrompt::unchanged);
    }

    // 初始化包含 skill prompt resolver 的完整 session manager
    public SessionManager(
            SessionStore store,
            Supplier<String> sessionIds,
            Supplier<String> runIds,
            Clock clock,
            SessionEventSink events,
            SessionRunExecutor runs,
            ConversationSummarizer summarizer,
            SessionPromptResolver promptResolver) {
        this.store = java.util.Objects.requireNonNull(store, "store");
        this.sessionIds = java.util.Objects.requireNonNull(sessionIds, "sessionIds");
        this.runIds = java.util.Objects.requireNonNull(runIds, "runIds");
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
        this.events = java.util.Objects.requireNonNull(events, "events");
        this.runs = java.util.Objects.requireNonNull(runs, "runs");
        this.summarizer = summarizer;
        this.promptResolver = java.util.Objects.requireNonNull(promptResolver, "promptResolver");
    }

    // 创建并持久化 active session 后发布 created 事件
    public CompletionStage<Session> create(SessionMode mode, String title) {
        java.util.Objects.requireNonNull(mode, "mode");
        java.util.Objects.requireNonNull(title, "title");
        String sessionId = java.util.Objects.requireNonNull(sessionIds.get(), "session ID");
        String timestamp = Instant.now(clock).toString();
        var session = new Session(
                sessionId,
                mode,
                SessionStatus.ACTIVE,
                title,
                timestamp,
                timestamp,
                java.util.List.of());
        try {
            store.writeMeta(session);
        } catch (java.io.IOException error) {
            return CompletableFuture.failedFuture(error);
        }
        return events.emit(new SessionCreatedEvent(
                        sessionId, mode.wireValue(), timestamp))
                .thenApply(ignored -> session);
    }

    // 从持久化 metadata 恢复 session 并返回容错读取的完整历史
    public CompletionStage<List<ObjectNode>> getHistory(String sessionId) {
        try {
            loadSession(sessionId);
            return CompletableFuture.completedFuture(store.readMessages(sessionId));
        } catch (Throwable error) {
            return CompletableFuture.failedFuture(error);
        }
    }

    // 返回按最近更新时间排序的全部 session metadata
    public CompletionStage<List<Session>> listSessions() {
        try {
            return CompletableFuture.completedFuture(store.listMeta());
        } catch (Throwable error) {
            return CompletableFuture.failedFuture(error);
        }
    }

    // 持久化一轮 user/assistant 消息并完成 session 状态转换
    public CompletionStage<String> sendMessage(String sessionId, String content) {
        java.util.Objects.requireNonNull(sessionId, "sessionId");
        java.util.Objects.requireNonNull(content, "content");
        AtomicBoolean lock = busy.computeIfAbsent(sessionId, ignored -> new AtomicBoolean());
        if (!lock.compareAndSet(false, true)) {
            return CompletableFuture.failedFuture(
                    new SessionException(SESSION_BUSY, "session busy"));
        }
        CompletionStage<String> completion;
        try {
            Session session = loadSession(sessionId);
            if (session.status() == SessionStatus.CLOSED) {
                throw new SessionException(SESSION_CLOSED, "session already closed");
            }
            String timestamp = Instant.now(clock).toString();
            String runId = java.util.Objects.requireNonNull(runIds.get(), "run ID");
            SessionPrompt prompt = java.util.Objects.requireNonNull(
                    promptResolver.resolve(content), "resolved session prompt");
            store.appendMessage(
                    sessionId,
                    "user",
                    io.klaude.protocol.ProtocolJson.mapper().getNodeFactory().textNode(content),
                    null);
            String title = session.title().isEmpty() ? firstCodePoints(content, 40) : session.title();
            Session running = new Session(
                    session.id(),
                    session.mode(),
                    session.status(),
                    title,
                    session.createdAt(),
                    timestamp,
                    appendRunId(session.runIds(), runId));
            store.writeMeta(running);
            SessionRunRequest request = new SessionRunRequest(
                    sessionId,
                    session.mode(),
                    runId,
                    prompt.goal(),
                    store.readMessages(sessionId),
                    store.readNotes(sessionId),
                    prompt.systemPromptOverride(),
                    prompt.allowedTools());
            CompletionStage<Void> lifecycle = session.status() == SessionStatus.WAITING_FOR_INPUT
                    ? events.emit(new io.klaude.protocol.SessionResumedEvent(sessionId, timestamp))
                    : CompletableFuture.completedFuture(null);
            completion = lifecycle
                    .thenCompose(ignored -> events.emit(new SessionMessageReceivedEvent(
                            sessionId, content, timestamp)))
                    .thenCompose(ignored -> prompt.skillName().isBlank()
                            ? CompletableFuture.completedFuture(null)
                            : events.emit(new io.klaude.protocol.SkillInvokedEvent(
                                    prompt.skillName(),
                                    prompt.arguments(),
                                    runId,
                                    timestamp)))
                    .thenCompose(ignored -> runs.run(request))
                    .thenCompose(messages -> finishChatTurn(running, runId, messages));
        } catch (Throwable error) {
            completion = CompletableFuture.failedFuture(error);
        }
        return completion.whenComplete((result, error) -> lock.set(false));
    }

    // 将 session 持久化为 closed 并发布关闭事件
    public CompletionStage<SessionStatus> close(String sessionId) {
        java.util.Objects.requireNonNull(sessionId, "sessionId");
        AtomicBoolean lock = busy.computeIfAbsent(sessionId, ignored -> new AtomicBoolean());
        if (!lock.compareAndSet(false, true)) {
            return CompletableFuture.failedFuture(
                    new SessionException(SESSION_BUSY, "session busy"));
        }
        CompletionStage<SessionStatus> completion;
        try {
            Session session = loadSession(sessionId);
            String timestamp = Instant.now(clock).toString();
            Session closed = new Session(
                    session.id(),
                    session.mode(),
                    SessionStatus.CLOSED,
                    session.title(),
                    session.createdAt(),
                    timestamp,
                    session.runIds());
            store.writeMeta(closed);
            completion = events.emit(new io.klaude.protocol.SessionClosedEvent(
                            sessionId, timestamp))
                    .thenApply(ignored -> SessionStatus.CLOSED);
        } catch (Throwable error) {
            completion = CompletableFuture.failedFuture(error);
        }
        return completion.whenComplete((result, error) -> lock.set(false));
    }

    // 使用可选 focus 手动压缩 session thread 并返回 token 节省量
    public CompletionStage<io.klaude.protocol.SessionCompactResult> compact(
            String sessionId, String focus) {
        java.util.Objects.requireNonNull(sessionId, "sessionId");
        java.util.Objects.requireNonNull(focus, "focus");
        if (summarizer == null) {
            return CompletableFuture.failedFuture(new SessionException(
                    COMPACTION_UNAVAILABLE, "provider not available for compaction"));
        }
        AtomicBoolean lock = busy.computeIfAbsent(sessionId, ignored -> new AtomicBoolean());
        if (!lock.compareAndSet(false, true)) {
            return CompletableFuture.failedFuture(
                    new SessionException(SESSION_BUSY, "session busy"));
        }
        CompletionStage<io.klaude.protocol.SessionCompactResult> completion;
        try {
            loadSession(sessionId);
            List<ObjectNode> messages = store.readMessages(sessionId);
            int originalTokens = estimateTokens(messages);
            var request = new ConversationSummaryRequest(
                    sessionId, messages, focus, originalTokens);
            completion = summarizer.summarize(request)
                    .thenCompose(summary -> applySummary(
                            sessionId, originalTokens, summary));
        } catch (Throwable error) {
            completion = CompletableFuture.failedFuture(error);
        }
        CompletionStage<io.klaude.protocol.SessionCompactResult> mapped =
                completion.exceptionallyCompose(error -> {
                    Throwable cause = unwrap(error);
                    if (cause instanceof SessionException sessionError) {
                        return CompletableFuture.failedFuture(sessionError);
                    }
                    return CompletableFuture.failedFuture(new SessionException(
                            COMPACTION_FAILED, "compaction failed or not beneficial"));
                });
        return mapped.whenComplete((result, error) -> lock.set(false));
    }

    // 原子写入合法摘要消息并构造 compact result
    private CompletionStage<io.klaude.protocol.SessionCompactResult> applySummary(
            String sessionId, int originalTokens, ConversationSummary summary) {
        try {
            if (summary == null || summary.text().isBlank()) {
                throw new SessionException(
                        COMPACTION_FAILED, "compaction failed or not beneficial");
            }
            ObjectNode summaryMessage = io.klaude.protocol.ProtocolJson.mapper()
                    .createObjectNode()
                    .put("role", "user")
                    .put("content", summary.text().strip());
            ObjectNode acknowledgement = io.klaude.protocol.ProtocolJson.mapper()
                    .createObjectNode()
                    .put("role", "assistant")
                    .put("content", "Understood, I'll continue from this summary.");
            store.writeCompacted(sessionId, List.of(summaryMessage, acknowledgement));
            return CompletableFuture.completedFuture(new io.klaude.protocol.SessionCompactResult(
                    summary.summaryTokens(),
                    Math.max(0, originalTokens - summary.summaryTokens())));
        } catch (Throwable error) {
            return CompletableFuture.failedFuture(error);
        }
    }

    // 使用 content Unicode 字符数除以四估算上下文 token
    private static int estimateTokens(List<ObjectNode> messages) {
        long characters = 0;
        for (ObjectNode message : messages) {
            String content = message.path("content").isTextual()
                    ? message.path("content").asText()
                    : message.path("content").toString();
            characters += content.codePointCount(0, content.length());
        }
        return (int) Math.min(Integer.MAX_VALUE, characters / 4);
    }

    // 追加 executor 输出并将 chat session 转为 waiting_for_input
    private CompletionStage<String> finishChatTurn(
            Session session, String runId, List<ObjectNode> messages) {
        try {
            for (ObjectNode message : java.util.Objects.requireNonNull(messages, "messages")) {
                store.appendMessage(
                        session.id(),
                        message.path("role").asText(),
                        message.get("content"),
                        runId);
            }
            String timestamp = Instant.now(clock).toString();
            SessionStatus status = session.mode() == SessionMode.ONE_SHOT
                    ? SessionStatus.CLOSED
                    : SessionStatus.WAITING_FOR_INPUT;
            Session finished = new Session(
                    session.id(),
                    session.mode(),
                    status,
                    session.title(),
                    session.createdAt(),
                    timestamp,
                    session.runIds());
            store.writeMeta(finished);
            if (status == SessionStatus.WAITING_FOR_INPUT) {
                return events.emit(new SessionWaitingForInputEvent(
                                session.id(), runId, timestamp))
                        .thenApply(ignored -> runId);
            }
            return events.emit(new io.klaude.protocol.SessionClosedEvent(
                            session.id(), timestamp))
                    .thenApply(ignored -> runId);
        } catch (Throwable error) {
            return CompletableFuture.failedFuture(error);
        }
    }

    // 创建追加 run ID 后的不可变列表
    private static List<String> appendRunId(List<String> existing, String runId) {
        var result = new java.util.ArrayList<>(existing);
        result.add(runId);
        return List.copyOf(result);
    }

    // 返回最多包含指定 Unicode code points 的字符串前缀
    private static String firstCodePoints(String value, int maximum) {
        int count = value.codePointCount(0, value.length());
        return count <= maximum
                ? value
                : value.substring(0, value.offsetByCodePoints(0, maximum));
    }

    // 从磁盘加载 session，并将缺失 metadata 映射为稳定域错误
    private Session loadSession(String sessionId) throws java.io.IOException {
        java.nio.file.Path metadata = store.sessionDirectory(sessionId).resolve("meta.json");
        if (!java.nio.file.Files.isRegularFile(metadata)) {
            throw new SessionException(SESSION_NOT_FOUND, "session not found");
        }
        return store.readMeta(sessionId);
    }

    // 解开 completion wrappers 以保留 session 域错误分类
    private static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while ((current instanceof java.util.concurrent.CompletionException
                        || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }
}
