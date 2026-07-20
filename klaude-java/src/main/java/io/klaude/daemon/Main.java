package io.klaude.daemon;

import io.klaude.daemon.config.ConfigLoader;
import io.klaude.agent.AgentRunner;
import io.klaude.agent.AgentConversationCompactor;
import io.klaude.agent.AgentRunRequest;
import io.klaude.agent.AgentToolExecutor;
import io.klaude.agent.BackgroundAgentRuns;
import io.klaude.agent.RunOutcome;
import io.klaude.agent.event.EventBus;
import io.klaude.llm.AnthropicProvider;
import io.klaude.llm.HttpAnthropicStreamClient;
import io.klaude.llm.LlmProvider;
import io.klaude.llm.LlmRequest;
import io.klaude.session.ConversationSummary;
import io.klaude.session.ConversationSummaryRequest;
import io.klaude.session.SessionManager;
import io.klaude.session.SessionRunRequest;
import io.klaude.session.SessionStore;
import io.klaude.session.task.TaskManager;
import io.klaude.tool.ToolContext;
import io.klaude.tool.ToolInvoker;
import io.klaude.tool.ToolRegistry;
import io.klaude.tool.permission.PermissionManager;
import io.klaude.tool.permission.PermissionPolicyStore;
import io.klaude.tool.permission.ToolPolicy;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import io.klaude.extension.profile.AgentProfile;
import io.klaude.extension.subagent.AgentResultTool;
import io.klaude.extension.subagent.SpawnAgentTool;
import io.klaude.extension.subagent.SubagentRunRequest;
import io.klaude.extension.subagent.SubagentRunner;

public final class Main {
    // 禁止实例化 daemon 主入口类
    private Main() {
    }

    // 加载配置、启动 daemon 并等待 JVM shutdown hook
    public static void main(String[] args) throws Exception {
        Path cwd = Path.of("").toAbsolutePath().normalize();
        Path home = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize();
        var config = new ConfigLoader().load(cwd, home, System.getenv());
        Clock clock = Clock.systemUTC();
        CountDownLatch shutdown = new CountDownLatch(1);
        AtomicReference<KlaudeDaemon> daemonReference = new AtomicReference<>();
        Path runsRoot = cwd.resolve("runs");
        Duration permissionTimeout = Duration.ofMillis(
                Math.round(config.permission().timeoutSeconds() * 1_000));
        String apiKey = System.getenv().getOrDefault("ANTHROPIC_API_KEY", "");
        URI endpoint = URI.create(System.getenv().getOrDefault(
                "ANTHROPIC_BASE_URL", "https://api.anthropic.com"));
        try (DaemonExtensionRuntime extensions = DaemonExtensionRuntime.production(
                     cwd, home, new io.klaude.mcp.DefaultMcpConnector());
             HttpAnthropicStreamClient anthropicClient = new HttpAnthropicStreamClient(endpoint, apiKey);
             PermissionManager permissions = new PermissionManager(
                     defaultPolicies(),
                     new PermissionPolicyStore(home.resolve(".klaude/policy.toml")),
                     event -> daemonReference.get().publish(event),
                     clock,
                     permissionTimeout)) {
            extensions.start(config.mcp().servers()).toCompletableFuture().get();
            LlmProvider provider = apiKey.isBlank()
                    ? (request, events) -> CompletableFuture.failedFuture(
                            new IllegalStateException("ANTHROPIC_API_KEY not set"))
                    : new AnthropicProvider(
                            config.llm().defaultModel(),
                            anthropicClient,
                            clock,
                            Main::retryDelay);
            var sessionStore = new SessionStore(
                    home.resolve(".klaude/sessions"),
                    clock,
                    config.compaction().toolResultLimit(),
                    config.compaction().toolResultKeep());
            var sessionManager = new SessionManager(
                    sessionStore,
                    () -> "sess-" + UUID.randomUUID().toString().replace("-", "")
                            .substring(0, 12),
                    () -> UUID.randomUUID().toString().replace("-", ""),
                    clock,
                    event -> daemonReference.get().publish(event),
                    request -> runSession(
                            request,
                            cwd,
                            home,
                            config.agent().maxSteps(),
                            config.compaction().autoThreshold(),
                            provider,
                            permissions,
                            sessionStore,
                            extensions,
                            daemonReference,
                            clock),
                    request -> summarizeConversation(provider, request),
                    extensions::resolvePrompt);
            try (BackgroundAgentRuns runs = new BackgroundAgentRuns(
                         () -> UUID.randomUUID().toString().replace("-", ""),
                         (runId, goal) -> runAgent(
                                 runId,
                                 goal,
                                 cwd,
                                 home,
                                 runsRoot,
                                 config.agent().maxSteps(),
                                 provider,
                                 permissions,
                                 extensions,
                                 daemonReference,
                                 clock));
             KlaudeDaemon daemon = new KlaudeDaemon(
                     config.host(),
                     config.port(),
                     runsRoot,
                     config.trace().file(),
                     clock,
                     () -> UUID.randomUUID().toString().replace("-", ""),
                     permissions,
                     runs,
                     sessionManager)) {
                daemonReference.set(daemon);
                Runtime.getRuntime().addShutdownHook(Thread.ofPlatform().unstarted(() -> {
                    daemon.close();
                    runs.close();
                    permissions.close();
                    anthropicClient.close();
                    shutdown.countDown();
                }));
                daemon.start().get();
                System.out.println("Klaude daemon listening at " + config.host() + ":" + daemon.port());
                shutdown.await();
            }
        }
    }

    // 为一个后台 run 组合工具、事件、provider 和 runner
    private static CompletionStage<RunOutcome> runAgent(
            String runId,
            String goal,
            Path cwd,
            Path home,
            Path runsRoot,
            int maxSteps,
            LlmProvider provider,
            PermissionManager permissions,
            DaemonExtensionRuntime extensions,
            AtomicReference<KlaudeDaemon> daemonReference,
            Clock clock) {
        try {
            var tasks = new TaskManager(runsRoot.resolve(runId).resolve(".tasks"), clock);
            var sessions = new SessionStore(home.resolve(".klaude/sessions"), clock);
            var runEvents = new EventBus();
            runEvents.subscribe(daemonReference.get()::publish);
            var registry = buildRegistry(
                    cwd, home, runsRoot, runId, runId, tasks, sessions, provider,
                    permissions, extensions, daemonReference, clock, maxSteps,
                    runEvents, 0, null);
            var invoker = new ToolInvoker(registry, permissions, runEvents::publish, clock);
            AgentToolExecutor executor = call -> invoker.invoke(
                    new ToolContext(cwd, runId, runId, call.id()),
                    call.name(),
                    call.input(),
                    Duration.ofSeconds(120));
            var runner = new AgentRunner(
                    provider, registry, executor, runEvents, clock, () -> runId, maxSteps);
            return runner.run(goal).whenComplete((outcome, error) -> invoker.close());
        } catch (Exception error) {
            return CompletableFuture.failedFuture(error);
        }
    }

    // 为一个 session turn 组合持久化历史、上下文、工具、自动压缩与 runner
    private static CompletionStage<java.util.List<com.fasterxml.jackson.databind.node.ObjectNode>> runSession(
            SessionRunRequest request,
            Path cwd,
            Path home,
            int maxSteps,
            double compactThreshold,
            LlmProvider provider,
            PermissionManager permissions,
            SessionStore sessions,
            DaemonExtensionRuntime extensions,
            AtomicReference<KlaudeDaemon> daemonReference,
            Clock clock) {
        try {
            Path sessionDirectory = sessions.sessionDirectory(request.sessionId());
            Path runDirectory = sessionDirectory.resolve("runs").resolve(request.runId());
            var tasks = new TaskManager(runDirectory.resolve(".tasks"), clock);
            var runEvents = new EventBus();
            runEvents.subscribe(daemonReference.get()::publish);
            var registry = buildRegistry(
                    cwd, home, sessionDirectory.resolve("runs"), request.runId(), request.sessionId(),
                    tasks, sessions, provider, permissions, extensions, daemonReference, clock,
                    maxSteps, runEvents, 0,
                    new AgentProfile(
                            "session", "", request.systemPromptOverride() == null
                                    ? "" : request.systemPromptOverride(),
                            request.allowedTools(), ""));
            var invoker = new ToolInvoker(registry, permissions, runEvents::publish, clock);
            AgentToolExecutor executor = call -> invoker.invoke(
                    new ToolContext(cwd, request.sessionId(), request.runId(), call.id()),
                    call.name(),
                    call.input(),
                    Duration.ofSeconds(120));
            var compactor = new AgentConversationCompactor(
                    provider, runEvents, sessionDirectory, request.sessionId(), clock);
            var runner = new AgentRunner(
                    provider,
                    registry,
                    executor,
                    runEvents,
                    clock,
                    request::runId,
                    maxSteps,
                    compactor,
                    compactThreshold);
            var agentRequest = new AgentRunRequest(
                    request.runId(),
                    request.goal(),
                    request.history(),
                    request.notes(),
                    readContext(home.resolve(".klaude/context.md")),
                    readContext(cwd.resolve(".klaude/context.md")),
                    request.systemPromptOverride());
            return runner.runCaptured(agentRequest)
                    .whenComplete((capture, error) -> invoker.close())
                    .thenApply(io.klaude.agent.AgentRunCapture::newMessages);
        } catch (Exception error) {
            return CompletableFuture.failedFuture(error);
        }
    }

    // 组合一个 run 的内置、MCP、spawn 与 result 工具，并应用 profile 白名单
    private static ToolRegistry buildRegistry(
            Path cwd,
            Path home,
            Path runsRoot,
            String runId,
            String sessionId,
            TaskManager tasks,
            SessionStore sessions,
            LlmProvider provider,
            PermissionManager permissions,
            DaemonExtensionRuntime extensions,
            AtomicReference<KlaudeDaemon> daemonReference,
            Clock clock,
            int maxSteps,
            EventBus events,
            int depth,
            AgentProfile profile) throws Exception {
        Set<String> allowed = profile == null ? Set.of() : Set.copyOf(profile.allowedTools());
        var extras = new ArrayList<io.klaude.tool.Tool>(extensions.tools());
        if (depth < 2 && (allowed.isEmpty() || allowed.contains("spawn_agent"))) {
            extras.add(new SpawnAgentTool(
                    child -> runChild(
                            child, cwd, home, runsRoot, provider, permissions, extensions,
                            daemonReference, clock, maxSteps, events),
                    events,
                    extensions.backgrounds(),
                    () -> UUID.randomUUID().toString().replace("-", ""),
                    extensions::profile,
                    clock,
                    depth));
        }
        if (depth < 2 && (allowed.isEmpty() || allowed.contains("agent_result"))) {
            extras.add(new AgentResultTool(extensions.backgrounds()));
        }
        return BuiltinTools.create(cwd, tasks, sessions, extras, allowed);
    }

    // 以冷启动上下文执行 child，并将其完成结果返回给父工具
    private static CompletionStage<RunOutcome> runChild(
            SubagentRunRequest request,
            Path cwd,
            Path home,
            Path runsRoot,
            LlmProvider provider,
            PermissionManager permissions,
            DaemonExtensionRuntime extensions,
            AtomicReference<KlaudeDaemon> daemonReference,
            Clock clock,
            int maxSteps,
            EventBus parentEvents) {
        try {
            Path childRoot = runsRoot.resolve(request.runId());
            var tasks = new TaskManager(childRoot.resolve(".tasks"), clock);
            var sessions = new SessionStore(home.resolve(".klaude/sessions"), clock);
            var childEvents = new EventBus();
            childEvents.subscribe(parentEvents::publish);
            var registry = buildRegistry(
                    cwd, home, runsRoot, request.runId(), request.sessionId(), tasks, sessions,
                    provider, permissions, extensions, daemonReference, clock, maxSteps, childEvents,
                    request.depth(), request.profile());
            var invoker = new ToolInvoker(registry, permissions, childEvents::publish, clock);
            AgentToolExecutor executor = call -> invoker.invoke(
                    new ToolContext(cwd, request.sessionId(), request.runId(), call.id()),
                    call.name(), call.input(), Duration.ofSeconds(120));
            var runner = new AgentRunner(
                    provider, registry, executor, childEvents, clock,
                    () -> request.runId(), maxSteps);
            String systemPrompt = request.profile() == null
                    ? null : request.profile().systemPrompt();
            var childRequest = new AgentRunRequest(
                    request.runId(), request.prompt(), List.of(), "", "", "", systemPrompt);
            return runner.runCaptured(childRequest)
                    .thenApply(io.klaude.agent.AgentRunCapture::outcome)
                    .whenComplete((outcome, error) -> invoker.close());
        } catch (Throwable error) {
            return CompletableFuture.failedFuture(error);
        }
    }

    // 通过静默 provider 调用为手动 compact 生成摘要与 token 统计
    private static CompletionStage<ConversationSummary> summarizeConversation(
            LlmProvider provider, ConversationSummaryRequest request) {
        var message = io.klaude.protocol.ProtocolJson.mapper().createObjectNode()
                .put("role", "user")
                .put("content", summaryPrompt(request));
        return provider.chat(
                        new LlmRequest(
                                "compact",
                                1,
                                java.util.List.of(message),
                                java.util.List.of(),
                                "You are a helpful assistant that summarizes conversations."),
                        event -> CompletableFuture.completedFuture(null))
                .thenApply(response -> new ConversationSummary(
                        response.text().strip(),
                        response.usage() == null
                                ? response.text().codePointCount(0, response.text().length()) / 4
                                : response.usage().outputTokens()));
    }

    // 将手动 compact 请求转换为包含 focus 的摘要 prompt
    private static String summaryPrompt(ConversationSummaryRequest request) {
        StringBuilder prompt = new StringBuilder(
                "Compress this conversation into a complete handoff summary.");
        if (!request.focus().isBlank()) {
            prompt.append("\n\nIMPORTANT: Pay special attention to: ")
                    .append(request.focus().strip());
        }
        prompt.append("\n\n---\n\n");
        for (var message : request.messages()) {
            prompt.append('[')
                    .append(message.path("role").asText("unknown")
                            .toUpperCase(java.util.Locale.ROOT))
                    .append("]\n")
                    .append(message.path("content").isTextual()
                            ? message.path("content").asText()
                            : message.path("content").toString())
                    .append("\n\n");
        }
        return prompt.toString();
    }

    // 以 UTF-8 读取可选 context 文件，缺失或不可读时返回空文本
    private static String readContext(Path path) {
        try {
            return Files.isRegularFile(path)
                    ? Files.readString(path, StandardCharsets.UTF_8)
                    : "";
        } catch (java.io.IOException ignored) {
            return "";
        }
    }

    // 通过 delayed executor 实现非阻塞 provider retry backoff
    private static CompletionStage<Void> retryDelay(Duration duration) {
        return CompletableFuture.runAsync(
                () -> { },
                CompletableFuture.delayedExecutor(duration.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS));
    }

    // 返回内置工具的默认权限策略
    private static Map<String, ToolPolicy> defaultPolicies() {
        return Map.of(
                "bash", ToolPolicy.ask(),
                "write_file", ToolPolicy.ask(),
                "read_file", ToolPolicy.allow(),
                "list_dir", ToolPolicy.allow(),
                "note_save", ToolPolicy.allow(),
                "task_create", ToolPolicy.ask(),
                "task_get", ToolPolicy.allow(),
                "task_list", ToolPolicy.allow(),
                "task_update", ToolPolicy.ask());
    }
}
