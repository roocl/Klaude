package io.klaude.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.klaude.protocol.Event;
import io.klaude.protocol.SessionMode;
import io.klaude.protocol.SessionStatus;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class SessionManagerTest {
    // 功能：验证创建 chat session 会持久化 active metadata 并发布 created 事件
    // 设计：注入固定 ID、Clock 和事件收集器，通过 manager 返回值及真实临时 store 观察行为
    @Test
    void createsActiveSessionAndPublishesEvent(@TempDir Path temp) throws Exception {
        Clock clock = Clock.fixed(Instant.parse("2026-07-19T14:00:00Z"), ZoneOffset.UTC);
        var store = new SessionStore(temp, clock);
        var events = new CopyOnWriteArrayList<Event>();
        var manager = new SessionManager(
                store,
                () -> "sess-fixed",
                clock,
                event -> {
                    events.add(event);
                    return java.util.concurrent.CompletableFuture.completedFuture(null);
                });

        Session created = manager.create(SessionMode.CHAT, "迁移会话")
                .toCompletableFuture().get();

        assertThat(created.id()).isEqualTo("sess-fixed");
        assertThat(created.status()).isEqualTo(SessionStatus.ACTIVE);
        assertThat(created.title()).isEqualTo("迁移会话");
        assertThat(created.createdAt()).isEqualTo("2026-07-19T14:00:00Z");
        assertThat(store.readMeta("sess-fixed")).isEqualTo(created);
        assertThat(events).singleElement().isInstanceOf(io.klaude.protocol.SessionCreatedEvent.class);
    }

    // 功能：验证新的 manager 实例可从磁盘恢复 session 并读取已有 thread 历史
    // 设计：第一实例创建并由 store 追加消息，第二实例不共享内存状态，仅通过公开 history 查询
    @Test
    void reloadsPersistedSessionHistoryAfterRestart(@TempDir Path temp) throws Exception {
        Clock clock = Clock.fixed(Instant.parse("2026-07-19T14:00:00Z"), ZoneOffset.UTC);
        var store = new SessionStore(temp, clock);
        SessionEventSink events = event -> java.util.concurrent.CompletableFuture.completedFuture(null);
        var first = new SessionManager(store, () -> "sess-fixed", clock, events);
        first.create(SessionMode.CHAT, "restart").toCompletableFuture().get();
        store.appendMessage(
                "sess-fixed",
                "user",
                io.klaude.protocol.ProtocolJson.mapper().getNodeFactory().textNode("继续迁移"),
                null);

        var restarted = new SessionManager(store, () -> "unused", clock, events);

        assertThat(restarted.getHistory("sess-fixed").toCompletableFuture().get())
                .singleElement()
                .satisfies(message -> {
                    assertThat(message.path("role").asText()).isEqualTo("user");
                    assertThat(message.path("content").asText()).isEqualTo("继续迁移");
                });
    }

    // 功能：验证 chat session 处理一轮消息后持久化完整历史并进入 waiting 状态
    // 设计：fake run executor 返回 assistant block，通过公开 send/history 与磁盘 meta 观察整个状态转换
    @Test
    void sendsChatMessageAndPersistsTurn(@TempDir Path temp) throws Exception {
        Clock clock = Clock.fixed(Instant.parse("2026-07-19T14:00:00Z"), ZoneOffset.UTC);
        var store = new SessionStore(temp, clock);
        var events = new CopyOnWriteArrayList<Event>();
        var request = new AtomicReference<SessionRunRequest>();
        SessionRunExecutor executor = turn -> {
            request.set(turn);
            var assistant = io.klaude.protocol.ProtocolJson.mapper().createObjectNode();
            assistant.put("role", "assistant");
            assistant.set("content", io.klaude.protocol.ProtocolJson.mapper().createArrayNode()
                    .add(io.klaude.protocol.ProtocolJson.mapper().createObjectNode()
                            .put("type", "text")
                            .put("text", "完成 " + turn.goal())));
            return java.util.concurrent.CompletableFuture.completedFuture(List.of(assistant));
        };
        var manager = new SessionManager(
                store,
                () -> "sess-fixed",
                () -> "run-fixed",
                clock,
                event -> {
                    events.add(event);
                    return java.util.concurrent.CompletableFuture.completedFuture(null);
                },
                executor);
        manager.create(SessionMode.CHAT, "").toCompletableFuture().get();
        events.clear();

        String runId = manager.sendMessage("sess-fixed", "继续迁移")
                .toCompletableFuture().get();

        assertThat(runId).isEqualTo("run-fixed");
        assertThat(request.get().history()).singleElement()
                .satisfies(message -> assertThat(message.path("content").asText())
                        .isEqualTo("继续迁移"));
        assertThat(manager.getHistory("sess-fixed").toCompletableFuture().get())
                .extracting(message -> message.path("role").asText())
                .containsExactly("user", "assistant");
        Session saved = store.readMeta("sess-fixed");
        assertThat(saved.status()).isEqualTo(SessionStatus.WAITING_FOR_INPUT);
        assertThat(saved.title()).isEqualTo("继续迁移");
        assertThat(saved.runIds()).containsExactly("run-fixed");
        assertThat(events).extracting(event -> event.getClass().getSimpleName())
                .containsExactly("SessionMessageReceivedEvent", "SessionWaitingForInputEvent");
    }

    // 功能：验证 slash skill 展开后向 run 传递 prompt、system prompt 与工具白名单
    // 设计：注入 fake resolver 并发送原始 slash 消息，观察请求、持久化历史和事件顺序
    @Test
    void resolvesSkillBeforeRunningSessionTurn(@TempDir Path temp) throws Exception {
        Clock clock = Clock.fixed(Instant.parse("2026-07-19T14:00:00Z"), ZoneOffset.UTC);
        var store = new SessionStore(temp, clock);
        var events = new CopyOnWriteArrayList<Event>();
        var request = new AtomicReference<SessionRunRequest>();
        var manager = new SessionManager(
                store,
                () -> "sess-fixed",
                () -> "run-fixed",
                clock,
                event -> {
                    events.add(event);
                    return java.util.concurrent.CompletableFuture.completedFuture(null);
                },
                turn -> {
                    request.set(turn);
                    return java.util.concurrent.CompletableFuture.completedFuture(List.of());
                },
                null,
                content -> new SessionPrompt(
                        "Review src/Main.java",
                        "You review $ARGUMENTS",
                        List.of("read_file"),
                        "review",
                        "src/Main.java"));
        manager.create(SessionMode.CHAT, "skill").toCompletableFuture().get();
        events.clear();

        manager.sendMessage("sess-fixed", "/review src/Main.java")
                .toCompletableFuture().get();

        assertThat(request.get().goal()).isEqualTo("Review src/Main.java");
        assertThat(request.get().systemPromptOverride()).isEqualTo("You review $ARGUMENTS");
        assertThat(request.get().allowedTools()).containsExactly("read_file");
        assertThat(store.readMessages("sess-fixed").getFirst().path("content").asText())
                .isEqualTo("/review src/Main.java");
        assertThat(events).extracting(event -> event.getClass().getSimpleName())
                .containsExactly(
                        "SessionMessageReceivedEvent",
                        "SkillInvokedEvent",
                        "SessionWaitingForInputEvent");
    }

    // 功能：验证显式关闭持久化 closed 状态且后续消息以稳定错误码拒绝
    // 设计：创建后调用公开 close，再 send 并观察 SessionException 及空 thread
    @Test
    void closedSessionRejectsFurtherMessages(@TempDir Path temp) throws Exception {
        Clock clock = Clock.fixed(Instant.parse("2026-07-19T14:00:00Z"), ZoneOffset.UTC);
        var store = new SessionStore(temp, clock);
        var manager = new SessionManager(
                store,
                () -> "sess-fixed",
                () -> "run-fixed",
                clock,
                event -> java.util.concurrent.CompletableFuture.completedFuture(null),
                request -> java.util.concurrent.CompletableFuture.completedFuture(List.of()));
        manager.create(SessionMode.CHAT, "closed").toCompletableFuture().get();

        manager.close("sess-fixed").toCompletableFuture().get();

        assertThat(store.readMeta("sess-fixed").status()).isEqualTo(SessionStatus.CLOSED);
        assertThatThrownBy(() -> manager.sendMessage("sess-fixed", "again")
                        .toCompletableFuture().join())
                .hasRootCauseInstanceOf(SessionException.class)
                .rootCause()
                .extracting(error -> ((SessionException) error).code())
                .isEqualTo(-32011);
        assertThat(store.readMessages("sess-fixed")).isEmpty();
    }

    // 功能：验证同一 session 的并发消息在首轮未完成时以 busy code 立即拒绝
    // 设计：executor 返回 pending future，先发第一条再发第二条，观察错误码与单条 user history
    @Test
    void rejectsConcurrentMessageForBusySession(@TempDir Path temp) throws Exception {
        Clock clock = Clock.fixed(Instant.parse("2026-07-19T14:00:00Z"), ZoneOffset.UTC);
        var store = new SessionStore(temp, clock);
        var pending = new java.util.concurrent.CompletableFuture<List<com.fasterxml.jackson.databind.node.ObjectNode>>();
        var manager = new SessionManager(
                store,
                () -> "sess-fixed",
                () -> "run-fixed",
                clock,
                event -> java.util.concurrent.CompletableFuture.completedFuture(null),
                request -> pending);
        manager.create(SessionMode.CHAT, "busy").toCompletableFuture().get();

        var first = manager.sendMessage("sess-fixed", "first").toCompletableFuture();
        var second = manager.sendMessage("sess-fixed", "second").toCompletableFuture();

        assertThatThrownBy(second::join)
                .hasRootCauseInstanceOf(SessionException.class)
                .rootCause()
                .extracting(error -> ((SessionException) error).code())
                .isEqualTo(-32012);
        assertThat(store.readMessages("sess-fixed"))
                .extracting(message -> message.path("content").asText())
                .containsExactly("first");
        pending.complete(List.of());
        assertThat(first.get()).isEqualTo("run-fixed");
    }

    // 功能：验证查询不存在的 session 返回稳定的 session_not_found code
    // 设计：对空临时 store 调用公开 history，观察域异常而非底层文件异常
    @Test
    void reportsMissingSessionWithStableCode(@TempDir Path temp) throws Exception {
        Clock clock = Clock.fixed(Instant.parse("2026-07-19T14:00:00Z"), ZoneOffset.UTC);
        var manager = new SessionManager(
                new SessionStore(temp, clock),
                () -> "unused",
                clock,
                event -> java.util.concurrent.CompletableFuture.completedFuture(null));

        assertThatThrownBy(() -> manager.getHistory("missing").toCompletableFuture().join())
                .hasRootCauseInstanceOf(SessionException.class)
                .rootCause()
                .extracting(error -> ((SessionException) error).code())
                .isEqualTo(-32010);
    }

    // 功能：验证 waiting session 的下一轮消息先发布 resumed 再完成新一轮生命周期
    // 设计：连续发送两条消息并在第二轮前清空事件，观察事件顺序和两个持久化 run ID
    @Test
    void resumesWaitingChatSessionForNextTurn(@TempDir Path temp) throws Exception {
        Clock clock = Clock.fixed(Instant.parse("2026-07-19T14:00:00Z"), ZoneOffset.UTC);
        var store = new SessionStore(temp, clock);
        var events = new CopyOnWriteArrayList<Event>();
        var runNumber = new java.util.concurrent.atomic.AtomicInteger();
        var manager = new SessionManager(
                store,
                () -> "sess-fixed",
                () -> "run-" + runNumber.incrementAndGet(),
                clock,
                event -> {
                    events.add(event);
                    return java.util.concurrent.CompletableFuture.completedFuture(null);
                },
                request -> java.util.concurrent.CompletableFuture.completedFuture(List.of()));
        manager.create(SessionMode.CHAT, "multi").toCompletableFuture().get();
        manager.sendMessage("sess-fixed", "first").toCompletableFuture().get();
        events.clear();

        manager.sendMessage("sess-fixed", "second").toCompletableFuture().get();

        assertThat(events).extracting(event -> event.getClass().getSimpleName())
                .containsExactly(
                        "SessionResumedEvent",
                        "SessionMessageReceivedEvent",
                        "SessionWaitingForInputEvent");
        assertThat(store.readMeta("sess-fixed").runIds()).containsExactly("run-1", "run-2");
    }

    // 功能：验证 one-shot session 在首轮完成后自动 closed 且不发布 waiting 事件
    // 设计：用立即完成的 executor 发送一条消息，观察持久化状态及最后一个事件类型
    @Test
    void oneShotSessionClosesAfterFirstTurn(@TempDir Path temp) throws Exception {
        Clock clock = Clock.fixed(Instant.parse("2026-07-19T14:00:00Z"), ZoneOffset.UTC);
        var store = new SessionStore(temp, clock);
        var events = new CopyOnWriteArrayList<Event>();
        var manager = new SessionManager(
                store,
                () -> "sess-fixed",
                () -> "run-fixed",
                clock,
                event -> {
                    events.add(event);
                    return java.util.concurrent.CompletableFuture.completedFuture(null);
                },
                request -> java.util.concurrent.CompletableFuture.completedFuture(List.of()));
        manager.create(SessionMode.ONE_SHOT, "once").toCompletableFuture().get();
        events.clear();

        manager.sendMessage("sess-fixed", "do it").toCompletableFuture().get();

        assertThat(store.readMeta("sess-fixed").status()).isEqualTo(SessionStatus.CLOSED);
        assertThat(events).extracting(event -> event.getClass().getSimpleName())
                .containsExactly("SessionMessageReceivedEvent", "SessionClosedEvent");
    }

    // 功能：验证手动 compact 传递 focus 并原子替换为合法的 user/assistant 摘要消息
    // 设计：真实 store 写入长历史，fake summarizer 捕获请求并返回固定 token 数，观察结果与新 thread
    @Test
    void manuallyCompactsSessionWithFocus(@TempDir Path temp) throws Exception {
        Clock clock = Clock.fixed(Instant.parse("2026-07-19T14:00:00Z"), ZoneOffset.UTC);
        var store = new SessionStore(temp, clock);
        var summaryRequest = new AtomicReference<ConversationSummaryRequest>();
        ConversationSummarizer summarizer = request -> {
            summaryRequest.set(request);
            return java.util.concurrent.CompletableFuture.completedFuture(
                    new ConversationSummary("## Summary\n完成迁移", 5));
        };
        var manager = new SessionManager(
                store,
                () -> "sess-fixed",
                () -> "run-fixed",
                clock,
                event -> java.util.concurrent.CompletableFuture.completedFuture(null),
                request -> java.util.concurrent.CompletableFuture.completedFuture(List.of()),
                summarizer);
        manager.create(SessionMode.CHAT, "compact").toCompletableFuture().get();
        store.appendMessage("sess-fixed", "user",
                io.klaude.protocol.ProtocolJson.mapper().getNodeFactory()
                        .textNode("x".repeat(100)), null);
        store.appendMessage("sess-fixed", "assistant",
                io.klaude.protocol.ProtocolJson.mapper().getNodeFactory()
                        .textNode("y".repeat(100)), "run-fixed");

        var result = manager.compact("sess-fixed", "只关注文件状态")
                .toCompletableFuture().get();

        assertThat(summaryRequest.get().focus()).isEqualTo("只关注文件状态");
        assertThat(result.summaryTokens()).isEqualTo(5);
        assertThat(result.savedTokens()).isEqualTo(45);
        assertThat(store.readMessages("sess-fixed"))
                .extracting(message -> message.path("role").asText())
                .containsExactly("user", "assistant");
        assertThat(store.readMessages("sess-fixed").getFirst().path("content").asText())
                .isEqualTo("## Summary\n完成迁移");
    }

    // 功能：验证 summarizer 失败时返回 compaction code 且原 thread 字节保持不变
    // 设计：真实 store 写历史后注入 failed future，比较 compact 前后文件并观察 -32021
    @Test
    void preservesThreadWhenCompactionSummaryFails(@TempDir Path temp) throws Exception {
        Clock clock = Clock.systemUTC();
        var store = new SessionStore(temp, clock);
        var manager = new SessionManager(
                store,
                () -> "sess-fixed",
                () -> "run-fixed",
                clock,
                event -> java.util.concurrent.CompletableFuture.completedFuture(null),
                request -> java.util.concurrent.CompletableFuture.completedFuture(List.of()),
                request -> java.util.concurrent.CompletableFuture.failedFuture(
                        new IllegalStateException("summary unavailable")));
        manager.create(SessionMode.CHAT, "compact").toCompletableFuture().get();
        store.appendMessage("sess-fixed", "user",
                io.klaude.protocol.ProtocolJson.mapper().getNodeFactory().textNode("original"), null);
        Path thread = store.sessionDirectory("sess-fixed").resolve("thread.jsonl");
        String before = java.nio.file.Files.readString(thread, java.nio.charset.StandardCharsets.UTF_8);

        assertThatThrownBy(() -> manager.compact("sess-fixed", "")
                        .toCompletableFuture().join())
                .hasRootCauseInstanceOf(SessionException.class)
                .rootCause()
                .extracting(error -> ((SessionException) error).code())
                .isEqualTo(-32021);
        assertThat(java.nio.file.Files.readString(
                thread, java.nio.charset.StandardCharsets.UTF_8)).isEqualTo(before);
    }
}
