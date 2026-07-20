package io.klaude.daemon;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.klaude.agent.AgentRunner;
import io.klaude.agent.BackgroundAgentRuns;
import io.klaude.agent.event.EventBus;
import io.klaude.llm.LlmResponse;
import io.klaude.llm.LlmStopReason;
import io.klaude.protocol.LlmTokenEvent;
import io.klaude.protocol.ProtocolJson;
import io.klaude.protocol.SessionMode;
import io.klaude.session.SessionManager;
import io.klaude.session.SessionStore;
import io.klaude.tool.ToolRegistry;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public final class OfflineDemoMain {
    // 禁止实例化离线 demo 入口
    private OfflineDemoMain() {
    }

    // 启动一次 ephemeral daemon scripted run 并输出 JSON 摘要
    public static void main(String[] args) throws Exception {
        Path temp = Files.createTempDirectory("klaude-offline-demo-");
        long started = System.nanoTime();
        List<ObjectNode> events = new ArrayList<>();
        AtomicReference<KlaudeDaemon> daemonReference = new AtomicReference<>();
        AtomicLong runStarted = new AtomicLong();
        AtomicLong firstTokenMillis = new AtomicLong(-1);
        io.klaude.llm.LlmProvider provider = (request, sink) -> {
            firstTokenMillis.compareAndSet(
                    -1,
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - runStarted.get()));
            return sink.emit(new LlmTokenEvent(
                            request.runId(), "offline demo complete", Instant.now().toString()))
                    .thenApply(ignored -> new LlmResponse(
                            LlmStopReason.END_TURN,
                            List.of(),
                            "offline demo complete",
                            null,
                            List.of()));
        };
        long startupMillis;
        long idleHeapBytes;
        long concurrentMillis;
        int concurrentSessions;
        ScenarioMetrics scenario;
        try (BackgroundAgentRuns runs = new BackgroundAgentRuns(
                     () -> "demo-run",
                     (runId, goal) -> {
                         var bus = new EventBus();
                         bus.subscribe(daemonReference.get()::publish);
                         return new AgentRunner(
                                 provider,
                                 new ToolRegistry(),
                                 call -> CompletableFuture.completedFuture(
                                         io.klaude.tool.ToolResult.success("unused")),
                                 bus,
                                 Clock.systemUTC(),
                                 () -> runId,
                                 5).run(goal);
                     });
             KlaudeDaemon daemon = new KlaudeDaemon(
                     "127.0.0.1",
                     0,
                     temp.resolve("runs"),
                     temp.resolve("trace.jsonl"),
                     Clock.systemUTC(),
                     () -> "demo-subscription",
                     null,
                     runs)) {
            daemonReference.set(daemon);
            long daemonStarted = System.nanoTime();
            daemon.start().get(5, TimeUnit.SECONDS);
            startupMillis = TimeUnit.NANOSECONDS.toMillis(
                    System.nanoTime() - daemonStarted);
            idleHeapBytes = java.lang.management.ManagementFactory.getMemoryMXBean()
                    .getHeapMemoryUsage().getUsed();
            scenario = runScenario(daemon.port(), events, runStarted);
            long concurrentStarted = System.nanoTime();
            concurrentSessions = exerciseConcurrentSessions(temp.resolve("sessions"));
            concurrentMillis = TimeUnit.NANOSECONDS.toMillis(
                    System.nanoTime() - concurrentStarted);
        } finally {
            deleteTree(temp);
        }
        ObjectNode summary = ProtocolJson.mapper().createObjectNode();
        summary.put("status", "success");
        summary.put("result", "offline demo complete");
        summary.put("elapsed_ms", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
        summary.put("startup_ms", startupMillis);
        summary.put("idle_heap_bytes", idleHeapBytes);
        summary.put("first_token_ms", firstTokenMillis.get());
        summary.put("full_run_ms", scenario.fullRunMillis());
        summary.put("concurrent_sessions", concurrentSessions);
        summary.put("concurrent_sessions_ms", concurrentMillis);
        summary.put("error_recovery", scenario.errorRecovery());
        summary.set("events", ProtocolJson.mapper().valueToTree(events));
        System.out.println(ProtocolJson.mapper().writeValueAsString(summary));
    }

    // 通过真实 socket 订阅事件并执行一次 agent.run
    private static ScenarioMetrics runScenario(
            int port, List<ObjectNode> events, AtomicLong runStarted) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", port);
             var writer = new OutputStreamWriter(
                     socket.getOutputStream(), StandardCharsets.UTF_8);
             var reader = new BufferedReader(new InputStreamReader(
                     socket.getInputStream(), StandardCharsets.UTF_8))) {
            socket.setSoTimeout(5_000);
            write(writer, "not-json");
            boolean parseError = ProtocolJson.mapper().readTree(reader.readLine())
                    .path("error").path("code").asInt() == -32700;
            write(writer, "{\"jsonrpc\":\"2.0\",\"id\":\"recovery\","
                    + "\"method\":\"core.ping\",\"params\":{"
                    + "\"type\":\"core.ping\",\"client\":\"offline-demo\"}}");
            var recovered = awaitResponse(reader, "recovery", events);
            boolean errorRecovery = parseError && recovered.path("result").has("server_version");
            write(writer, "{\"jsonrpc\":\"2.0\",\"id\":\"subscribe\","
                    + "\"method\":\"event.subscribe\",\"params\":{"
                    + "\"type\":\"event.subscribe\",\"topics\":[\"run.*\",\"step.*\"],"
                    + "\"scope\":\"global\"}}");
            awaitResponse(reader, "subscribe", events);
            runStarted.set(System.nanoTime());
            write(writer, "{\"jsonrpc\":\"2.0\",\"id\":\"run\","
                    + "\"method\":\"agent.run\",\"params\":{"
                    + "\"type\":\"agent.run\",\"goal\":\"offline demo\"}}");
            boolean responseReceived = false;
            boolean finished = false;
            while (!responseReceived || !finished) {
                var envelope = ProtocolJson.mapper().readTree(reader.readLine());
                if (envelope.path("kind").asText().equals("event")) {
                    ObjectNode event = (ObjectNode) envelope.path("event").deepCopy();
                    events.add(event);
                    finished |= event.path("type").asText().equals("run.finished");
                } else if (envelope.path("id").asText().equals("run")) {
                    responseReceived = true;
                }
            }
            return new ScenarioMetrics(
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - runStarted.get()),
                    errorRecovery);
        }
    }

    // 并发执行四个隔离 session turn 并返回完成数量
    private static int exerciseConcurrentSessions(Path root) throws Exception {
        Files.createDirectories(root);
        AtomicInteger sessionIds = new AtomicInteger();
        AtomicInteger runIds = new AtomicInteger();
        var store = new SessionStore(root, Clock.systemUTC());
        var manager = new SessionManager(
                store,
                () -> "demo-session-" + sessionIds.incrementAndGet(),
                () -> "demo-session-run-" + runIds.incrementAndGet(),
                Clock.systemUTC(),
                event -> CompletableFuture.completedFuture(null),
                request -> {
                    ObjectNode assistant = ProtocolJson.mapper().createObjectNode();
                    assistant.put("role", "assistant");
                    assistant.put("content", "done");
                    return CompletableFuture.completedFuture(List.of(assistant));
                });
        var sessions = new ArrayList<io.klaude.session.Session>();
        for (int index = 0; index < 4; index++) {
            sessions.add(manager.create(SessionMode.CHAT, "demo")
                    .toCompletableFuture().get());
        }
        var turns = sessions.stream()
                .map(session -> manager.sendMessage(session.id(), "concurrent demo")
                        .toCompletableFuture())
                .toList();
        CompletableFuture.allOf(turns.toArray(CompletableFuture[]::new)).get();
        return turns.size();
    }

    // 写出一条 JSON-RPC NDJSON 消息
    private static void write(OutputStreamWriter writer, String message) throws Exception {
        writer.write(message);
        writer.write('\n');
        writer.flush();
    }

    // 等待指定响应并保留期间交错到达的事件
    private static com.fasterxml.jackson.databind.JsonNode awaitResponse(
            BufferedReader reader, String id, List<ObjectNode> events) throws Exception {
        while (true) {
            var envelope = ProtocolJson.mapper().readTree(reader.readLine());
            if (envelope.path("kind").asText().equals("event")) {
                events.add((ObjectNode) envelope.path("event").deepCopy());
            } else if (envelope.path("id").asText().equals(id)) {
                return envelope;
            }
        }
    }

    // 递归删除 demo 拥有的临时目录
    private static void deleteTree(Path root) {
        try (var paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (java.io.IOException ignored) {
                    // Temporary demo cleanup is best effort after all owned resources close.
                }
            });
        } catch (java.io.IOException ignored) {
            // Missing or already removed temporary root needs no further cleanup.
        }
    }

    private record ScenarioMetrics(long fullRunMillis, boolean errorRecovery) {
        // 保存完整 fake run 与错误恢复指标
    }
}
