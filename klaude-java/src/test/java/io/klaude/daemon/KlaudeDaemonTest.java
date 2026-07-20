package io.klaude.daemon;

import static org.assertj.core.api.Assertions.assertThat;

import io.klaude.protocol.ProtocolJson;
import io.klaude.observability.EventStore;
import io.klaude.observability.TraceStore;
import io.klaude.tool.PermissionOutcome;
import io.klaude.tool.ToolContext;
import io.klaude.tool.builtin.BashTool;
import io.klaude.tool.permission.PermissionManager;
import io.klaude.tool.permission.PermissionPolicyStore;
import io.klaude.tool.permission.ToolPolicy;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class KlaudeDaemonTest {
    // 功能：验证 daemon 提供 ping、core.started 订阅快照并在关闭后立即释放端口
    // 设计：固定 Clock 启动 ephemeral server，用真实 Socket 完成两条命令后在同一端口重绑 ServerSocket
    @Test
    void servesPingStartedSubscriptionAndReleasesPort(@TempDir Path temp) throws Exception {
        Clock clock = Clock.fixed(Instant.parse("2026-07-19T10:15:30Z"), ZoneOffset.UTC);
        int port;
        try (KlaudeDaemon daemon = new KlaudeDaemon(
                "127.0.0.1",
                0,
                temp.resolve("runs"),
                temp.resolve("trace.jsonl"),
                clock,
                () -> "12345678")) {
            daemon.start().get(5, java.util.concurrent.TimeUnit.SECONDS);
            port = daemon.port();
            try (Socket socket = new Socket("127.0.0.1", port);
                 var writer = new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8);
                 var reader = new BufferedReader(new InputStreamReader(
                         socket.getInputStream(), StandardCharsets.UTF_8))) {
                socket.setSoTimeout(5_000);
                writer.write("{\"jsonrpc\":\"2.0\",\"id\":\"ping\",\"method\":\"core.ping\","
                        + "\"params\":{\"client\":\"java-test\"}}\n");
                writer.flush();
                var ping = ProtocolJson.mapper().readTree(reader.readLine());
                assertThat(ping.path("result").path("server_version").asText()).isEqualTo("0.1.0");
                assertThat(ping.path("result").path("received_at").asText())
                        .isEqualTo("2026-07-19T10:15:30Z");

                writer.write("{\"jsonrpc\":\"2.0\",\"id\":\"subscribe\","
                        + "\"method\":\"event.subscribe\","
                        + "\"params\":{\"topics\":[\"core.*\"],\"scope\":\"global\"}}\n");
                writer.flush();
                var first = ProtocolJson.mapper().readTree(reader.readLine());
                var second = ProtocolJson.mapper().readTree(reader.readLine());
                var push = first.path("kind").asText().equals("event") ? first : second;
                var response = first.has("jsonrpc") ? first : second;
                assertThat(response.path("result").path("subscription_id").asText())
                        .isEqualTo("sub-12345678");
                assertThat(push.path("event").path("type").asText()).isEqualTo("core.started");
                assertThat(push.path("event").path("listen_addr").asText())
                        .isEqualTo("127.0.0.1:" + port);
            }
        }

        try (ServerSocket rebound = new ServerSocket(port)) {
            assertThat(rebound.isBound()).isTrue();
        }
        assertThat(new TraceStore(temp.resolve("trace.jsonl")).readAll())
                .extracting(trace -> trace.path("kind").asText())
                .contains("push");
    }

    // 功能：验证 event.subscribe 先回放匹配历史，再无缝衔接实时事件
    // 设计：启动前写两个不同 topic 事件，订阅 run.* 后检查单条 replay、响应计数和随后 live 顺序
    @Test
    void replaysMatchingEventsBeforeLiveDelivery(@TempDir Path temp) throws Exception {
        var mapper = ProtocolJson.mapper();
        Path runs = temp.resolve("runs");
        EventStore history = new EventStore(runs.resolve("run-001/events.jsonl"));
        history.append(mapper.createObjectNode()
                .put("type", "run.started")
                .put("run_id", "run-001")
                .put("goal", "history")
                .put("ts", "2026-07-19T10:00:00Z"));
        history.append(mapper.createObjectNode()
                .put("type", "step.started")
                .put("run_id", "run-001")
                .put("step", 1)
                .put("ts", "2026-07-19T10:00:01Z"));
        Clock clock = Clock.fixed(Instant.parse("2026-07-19T10:15:30Z"), ZoneOffset.UTC);
        try (KlaudeDaemon daemon = new KlaudeDaemon(
                "127.0.0.1", 0, runs, temp.resolve("trace.jsonl"), clock, () -> "12345678")) {
            daemon.start().get(5, java.util.concurrent.TimeUnit.SECONDS);
            try (Socket socket = new Socket("127.0.0.1", daemon.port());
                 var writer = new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8);
                 var reader = new BufferedReader(new InputStreamReader(
                         socket.getInputStream(), StandardCharsets.UTF_8))) {
                socket.setSoTimeout(5_000);
                writer.write("{\"jsonrpc\":\"2.0\",\"id\":\"subscribe\","
                        + "\"method\":\"event.subscribe\","
                        + "\"params\":{\"topics\":[\"run.*\"],\"scope\":\"global\","
                        + "\"replay_from_run\":\"run-001\"}}\n");
                writer.flush();

                var replay = mapper.readTree(reader.readLine());
                var response = mapper.readTree(reader.readLine());
                assertThat(replay.path("event").path("goal").asText()).isEqualTo("history");
                assertThat(response.path("result").path("replayed_count").asInt()).isEqualTo(1);

                daemon.publish(mapper.createObjectNode()
                                .put("type", "run.finished")
                                .put("run_id", "run-001")
                                .put("status", "success")
                                .put("ts", "2026-07-19T10:15:31Z"))
                        .toCompletableFuture().get(5, java.util.concurrent.TimeUnit.SECONDS);
                var live = mapper.readTree(reader.readLine());
                assertThat(live.path("event").path("type").asText()).isEqualTo("run.finished");
            }
        }

        var traces = new TraceStore(temp.resolve("trace.jsonl")).readAll();
        assertThat(traces).extracting(trace -> trace.path("kind").asText())
                .contains("command", "response", "push");
        assertThat(traces).extracting(trace -> trace.path("direction").asText())
                .contains("CLIENT→CORE", "CORE→CLIENT");
    }

    // 功能：验证 daemon 推送 permission request 并通过 permission.respond 解决 pending future
    // 设计：真实 Socket 订阅 permission.*，触发 bash ASK，回送 allow_once 后观察 RPC 与 outcome
    @Test
    void streamsAndResolvesPermissionRequest(@TempDir Path temp) throws Exception {
        AtomicReference<KlaudeDaemon> daemonReference = new AtomicReference<>();
        try (PermissionManager permissions = new PermissionManager(
                Map.of("bash", ToolPolicy.ask()),
                new PermissionPolicyStore(temp.resolve("policy.toml")),
                event -> daemonReference.get().publish(event),
                Clock.systemUTC(),
                Duration.ofSeconds(5));
             KlaudeDaemon daemon = new KlaudeDaemon(
                     "127.0.0.1",
                     0,
                     temp.resolve("runs"),
                     temp.resolve("trace.jsonl"),
                     Clock.systemUTC(),
                     () -> "12345678",
                     permissions)) {
            daemonReference.set(daemon);
            daemon.start().get(5, java.util.concurrent.TimeUnit.SECONDS);
            try (Socket socket = new Socket("127.0.0.1", daemon.port());
                 var writer = new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8);
                 var reader = new BufferedReader(new InputStreamReader(
                         socket.getInputStream(), StandardCharsets.UTF_8))) {
                socket.setSoTimeout(5_000);
                writer.write("{\"jsonrpc\":\"2.0\",\"id\":\"subscribe\","
                        + "\"method\":\"event.subscribe\","
                        + "\"params\":{\"topics\":[\"permission.*\"],\"scope\":\"global\"}}\n");
                writer.flush();
                assertThat(ProtocolJson.mapper().readTree(reader.readLine())
                        .path("result").path("subscription_id").asText())
                        .isEqualTo("sub-12345678");

                var pending = permissions.check(
                        new ToolContext(temp, "session-001", "run-001", "tool-001"),
                        new BashTool(temp),
                        ProtocolJson.mapper().createObjectNode().put("command", "echo hi"));
                var request = ProtocolJson.mapper().readTree(reader.readLine());
                assertThat(request.path("event").path("type").asText())
                        .isEqualTo("permission.requested");

                writer.write("{\"jsonrpc\":\"2.0\",\"id\":\"respond\","
                        + "\"method\":\"permission.respond\","
                        + "\"params\":{\"tool_use_id\":\"tool-001\","
                        + "\"decision\":\"allow_once\"}}\n");
                writer.flush();
                var first = ProtocolJson.mapper().readTree(reader.readLine());
                var second = ProtocolJson.mapper().readTree(reader.readLine());
                var response = first.has("jsonrpc") ? first : second;
                var granted = first.path("kind").asText().equals("event") ? first : second;

                assertThat(response.path("result").path("ok").asBoolean()).isTrue();
                assertThat(granted.path("event").path("type").asText())
                        .isEqualTo("permission.granted");
                assertThat(pending.toCompletableFuture().get())
                        .isEqualTo(PermissionOutcome.allow("allow_once"));
            }
        }
    }

    // 功能：验证 agent.run 立即返回 launcher 分配的 run ID
    // 设计：真实 Socket 发送 Unicode goal，用 fake launcher 捕获参数并观察 JSON-RPC 结果
    @Test
    void launchesAgentRunAndReturnsIdImmediately(@TempDir Path temp) throws Exception {
        AtomicReference<String> goal = new AtomicReference<>();
        try (KlaudeDaemon daemon = new KlaudeDaemon(
                "127.0.0.1",
                0,
                temp.resolve("runs"),
                temp.resolve("trace.jsonl"),
                Clock.systemUTC(),
                () -> "12345678",
                null,
                requestedGoal -> {
                    goal.set(requestedGoal);
                    return "run-fixed";
                })) {
            daemon.start().get(5, java.util.concurrent.TimeUnit.SECONDS);
            try (Socket socket = new Socket("127.0.0.1", daemon.port());
                 var writer = new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8);
                 var reader = new BufferedReader(new InputStreamReader(
                         socket.getInputStream(), StandardCharsets.UTF_8))) {
                socket.setSoTimeout(5_000);
                writer.write("{\"jsonrpc\":\"2.0\",\"id\":\"run\","
                        + "\"method\":\"agent.run\","
                        + "\"params\":{\"goal\":\"完成迁移\"}}\n");
                writer.flush();

                var response = ProtocolJson.mapper().readTree(reader.readLine());
                assertThat(response.path("result").path("run_id").asText())
                        .isEqualTo("run-fixed");
                assertThat(goal).hasValue("完成迁移");
            }
        }
    }

    // 功能：验证 daemon 通过 IPC 创建、读取历史并关闭持久化 session
    // 设计：真实临时 SessionManager 与 TCP socket 连续发送三个协议命令，观察兼容 result 字段
    @Test
    void createsReadsAndClosesSessionOverIpc(@TempDir Path temp) throws Exception {
        Clock clock = Clock.fixed(
                java.time.Instant.parse("2026-07-19T14:00:00Z"),
                java.time.ZoneOffset.UTC);
        var daemonReference = new AtomicReference<KlaudeDaemon>();
        var sessions = new io.klaude.session.SessionManager(
                new io.klaude.session.SessionStore(temp.resolve("sessions"), clock),
                () -> "sess-fixed",
                clock,
                event -> daemonReference.get().publish(event));
        try (KlaudeDaemon daemon = new KlaudeDaemon(
                "127.0.0.1",
                0,
                temp.resolve("runs"),
                temp.resolve("trace.jsonl"),
                clock,
                () -> "12345678",
                null,
                null,
                sessions)) {
            daemonReference.set(daemon);
            daemon.start().get(5, java.util.concurrent.TimeUnit.SECONDS);
            try (Socket socket = new Socket("127.0.0.1", daemon.port());
                 var writer = new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8);
                 var reader = new BufferedReader(new InputStreamReader(
                         socket.getInputStream(), StandardCharsets.UTF_8))) {
                socket.setSoTimeout(5_000);
                writer.write("{\"jsonrpc\":\"2.0\",\"id\":\"create\","
                        + "\"method\":\"session.create\","
                        + "\"params\":{\"mode\":\"chat\",\"title\":\"ipc test\"}}\n");
                writer.flush();
                var created = ProtocolJson.mapper().readTree(reader.readLine());
                assertThat(created.path("result").path("session_id").asText())
                        .isEqualTo("sess-fixed");
                assertThat(created.path("result").path("status").asText()).isEqualTo("active");

                writer.write("{\"jsonrpc\":\"2.0\",\"id\":\"history\","
                        + "\"method\":\"session.get_history\","
                        + "\"params\":{\"session_id\":\"sess-fixed\"}}\n");
                writer.flush();
                var history = ProtocolJson.mapper().readTree(reader.readLine());
                assertThat(history.path("result").path("messages")).isEmpty();

                writer.write("{\"jsonrpc\":\"2.0\",\"id\":\"close\","
                        + "\"method\":\"session.close\","
                        + "\"params\":{\"session_id\":\"sess-fixed\"}}\n");
                writer.flush();
                var closed = ProtocolJson.mapper().readTree(reader.readLine());
                assertThat(closed.path("result").path("status").asText()).isEqualTo("closed");
            }
        }
    }

    // 功能：验证 session 域错误码通过 daemon JSON-RPC 原样返回
    // 设计：对空 store 查询 missing history，观察 error.code=-32010 而非 internal error
    @Test
    void returnsSessionDomainErrorCodeOverIpc(@TempDir Path temp) throws Exception {
        Clock clock = Clock.systemUTC();
        var sessions = new io.klaude.session.SessionManager(
                new io.klaude.session.SessionStore(temp.resolve("sessions"), clock),
                () -> "unused",
                clock,
                event -> java.util.concurrent.CompletableFuture.completedFuture(null));
        try (KlaudeDaemon daemon = new KlaudeDaemon(
                "127.0.0.1",
                0,
                temp.resolve("runs"),
                temp.resolve("trace.jsonl"),
                clock,
                () -> "12345678",
                null,
                null,
                sessions)) {
            daemon.start().get(5, java.util.concurrent.TimeUnit.SECONDS);
            try (Socket socket = new Socket("127.0.0.1", daemon.port());
                 var writer = new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8);
                 var reader = new BufferedReader(new InputStreamReader(
                         socket.getInputStream(), StandardCharsets.UTF_8))) {
                socket.setSoTimeout(5_000);
                writer.write("{\"jsonrpc\":\"2.0\",\"id\":\"missing\","
                        + "\"method\":\"session.get_history\","
                        + "\"params\":{\"session_id\":\"missing\"}}\n");
                writer.flush();

                var response = ProtocolJson.mapper().readTree(reader.readLine());
                assertThat(response.path("error").path("code").asInt()).isEqualTo(-32010);
                assertThat(response.path("error").path("message").asText())
                        .isEqualTo("session not found");
            }
        }
    }

    // 功能：验证 session.send_message 通过 daemon 返回 run ID 并持久化 fake turn
    // 设计：真实 TCP 创建 session 后发送消息，fake executor 返回 assistant，再通过 history 查询角色顺序
    @Test
    void sendsSessionMessageOverIpc(@TempDir Path temp) throws Exception {
        Clock clock = Clock.systemUTC();
        var sessions = new io.klaude.session.SessionManager(
                new io.klaude.session.SessionStore(temp.resolve("sessions"), clock),
                () -> "sess-fixed",
                () -> "run-fixed",
                clock,
                event -> java.util.concurrent.CompletableFuture.completedFuture(null),
                request -> {
                    var assistant = ProtocolJson.mapper().createObjectNode();
                    assistant.put("role", "assistant");
                    assistant.put("content", "done");
                    return java.util.concurrent.CompletableFuture.completedFuture(List.of(assistant));
                });
        try (KlaudeDaemon daemon = new KlaudeDaemon(
                "127.0.0.1", 0, temp.resolve("runs"), temp.resolve("trace.jsonl"),
                clock, () -> "12345678", null, null, sessions)) {
            daemon.start().get(5, java.util.concurrent.TimeUnit.SECONDS);
            try (Socket socket = new Socket("127.0.0.1", daemon.port());
                 var writer = new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8);
                 var reader = new BufferedReader(new InputStreamReader(
                         socket.getInputStream(), StandardCharsets.UTF_8))) {
                socket.setSoTimeout(5_000);
                writer.write("{\"jsonrpc\":\"2.0\",\"id\":\"create\","
                        + "\"method\":\"session.create\",\"params\":{}}\n");
                writer.flush();
                ProtocolJson.mapper().readTree(reader.readLine());

                writer.write("{\"jsonrpc\":\"2.0\",\"id\":\"send\","
                        + "\"method\":\"session.send_message\","
                        + "\"params\":{\"session_id\":\"sess-fixed\",\"content\":\"hello\"}}\n");
                writer.flush();
                var sent = ProtocolJson.mapper().readTree(reader.readLine());
                assertThat(sent.path("result").path("run_id").asText()).isEqualTo("run-fixed");

                writer.write("{\"jsonrpc\":\"2.0\",\"id\":\"history\","
                        + "\"method\":\"session.get_history\","
                        + "\"params\":{\"session_id\":\"sess-fixed\"}}\n");
                writer.flush();
                var history = ProtocolJson.mapper().readTree(reader.readLine())
                        .path("result").path("messages");
                assertThat(history).extracting(message -> message.path("role").asText())
                        .containsExactly("user", "assistant");
            }
        }
    }

    // 功能：验证 session.compact 通过 IPC 返回 token 统计并写入合法摘要历史
    // 设计：真实 store 预写历史、fake summarizer 返回固定摘要，TCP compact 后查询 history
    @Test
    void compactsSessionOverIpc(@TempDir Path temp) throws Exception {
        Clock clock = Clock.fixed(Instant.parse("2026-07-19T14:00:00Z"), ZoneOffset.UTC);
        var store = new io.klaude.session.SessionStore(temp.resolve("sessions"), clock);
        var sessions = new io.klaude.session.SessionManager(
                store,
                () -> "sess-fixed",
                () -> "run-fixed",
                clock,
                event -> java.util.concurrent.CompletableFuture.completedFuture(null),
                request -> java.util.concurrent.CompletableFuture.completedFuture(List.of()),
                request -> java.util.concurrent.CompletableFuture.completedFuture(
                        new io.klaude.session.ConversationSummary("summary", 1)));
        sessions.create(io.klaude.protocol.SessionMode.CHAT, "compact")
                .toCompletableFuture().get();
        store.appendMessage("sess-fixed", "user",
                ProtocolJson.mapper().getNodeFactory().textNode("x".repeat(40)), null);
        try (KlaudeDaemon daemon = new KlaudeDaemon(
                "127.0.0.1", 0, temp.resolve("runs"), temp.resolve("trace.jsonl"),
                clock, () -> "12345678", null, null, sessions)) {
            daemon.start().get(5, java.util.concurrent.TimeUnit.SECONDS);
            try (Socket socket = new Socket("127.0.0.1", daemon.port());
                 var writer = new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8);
                 var reader = new BufferedReader(new InputStreamReader(
                         socket.getInputStream(), StandardCharsets.UTF_8))) {
                socket.setSoTimeout(5_000);
                writer.write("{\"jsonrpc\":\"2.0\",\"id\":\"compact\","
                        + "\"method\":\"session.compact\","
                        + "\"params\":{\"session_id\":\"sess-fixed\",\"focus\":\"files\"}}\n");
                writer.flush();
                var result = ProtocolJson.mapper().readTree(reader.readLine()).path("result");
                assertThat(result.path("summary_tokens").asInt()).isEqualTo(1);
                assertThat(result.path("saved_tokens").asInt()).isEqualTo(9);

                writer.write("{\"jsonrpc\":\"2.0\",\"id\":\"history\","
                        + "\"method\":\"session.get_history\","
                        + "\"params\":{\"session_id\":\"sess-fixed\"}}\n");
                writer.flush();
                var history = ProtocolJson.mapper().readTree(reader.readLine())
                        .path("result").path("messages");
                assertThat(history).extracting(message -> message.path("role").asText())
                        .containsExactly("user", "assistant");
            }
        }
    }
}
