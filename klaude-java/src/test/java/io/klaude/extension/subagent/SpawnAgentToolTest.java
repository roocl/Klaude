package io.klaude.extension.subagent;

import static org.assertj.core.api.Assertions.assertThat;

import io.klaude.agent.RunOutcome;
import io.klaude.agent.event.EventBus;
import io.klaude.protocol.Event;
import io.klaude.protocol.ProtocolJson;
import io.klaude.tool.ToolContext;
import java.nio.file.Path;
import java.time.Clock;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

final class SpawnAgentToolTest {
    // 功能：验证前台 spawn_agent 发布父子事件并等待 child outcome 返回文本
    // 设计：fake runner 捕获隔离请求并立即成功，通过标准 Tool execute 观察事件和结果
    @Test
    void foregroundSpawnPublishesLifecycleAndReturnsChildResult() throws Exception {
        var events = new CopyOnWriteArrayList<Event>();
        var bus = new EventBus();
        bus.subscribe(event -> {
            events.add(event);
            return CompletableFuture.completedFuture(null);
        });
        var request = new AtomicReference<SubagentRunRequest>();
        SubagentRunner runner = child -> {
            request.set(child);
            return CompletableFuture.completedFuture(new RunOutcome(
                    child.runId(), "success", "analysis complete", null, 1));
        };
        var tool = new SpawnAgentTool(
                runner,
                bus,
                new BackgroundSubagentRegistry(),
                () -> "child-fixed",
                name -> java.util.Optional.empty(),
                Clock.systemUTC(),
                0);
        var params = ProtocolJson.mapper().createObjectNode()
                .put("description", "analyze code")
                .put("prompt", "Inspect src and report findings");

        var result = tool.execute(
                        new ToolContext(Path.of("."), "session", "parent-run", "call"), params)
                .toCompletableFuture().get();

        assertThat(request.get().prompt()).isEqualTo("Inspect src and report findings");
        assertThat(request.get().parentRunId()).isEqualTo("parent-run");
        assertThat(result.isError()).isFalse();
        assertThat(result.content()).isEqualTo("analysis complete");
        assertThat(events).extracting(event -> event.getClass().getSimpleName())
                .containsExactly("SubagentStartedEvent", "SubagentFinishedEvent");
    }

    // 功能：验证后台 spawn 立即返回、pending 不消费、完成结果只可领取一次
    // 设计：fake runner 返回 pending future，依次调用 spawn/result、完成 future、两次 result
    @Test
    void backgroundResultIsPendingThenConsumedOnce() throws Exception {
        var bus = new EventBus();
        var events = new CopyOnWriteArrayList<Event>();
        bus.subscribe(event -> {
            events.add(event);
            return CompletableFuture.completedFuture(null);
        });
        var pending = new CompletableFuture<RunOutcome>();
        var registry = new BackgroundSubagentRegistry();
        var spawn = new SpawnAgentTool(
                request -> pending,
                bus,
                registry,
                () -> "child-bg",
                name -> java.util.Optional.empty(),
                Clock.systemUTC(),
                0);
        var params = ProtocolJson.mapper().createObjectNode()
                .put("description", "background task")
                .put("prompt", "work independently")
                .put("run_in_background", true);

        var started = spawn.execute(
                        new ToolContext(Path.of("."), "session", "parent-run", "call"), params)
                .toCompletableFuture().get();
        var results = new AgentResultTool(registry);
        var query = ProtocolJson.mapper().createObjectNode().put("run_id", "child-bg");
        var stillRunning = results.execute(
                        new ToolContext(Path.of("."), "session", "parent-run", "result"), query)
                .toCompletableFuture().get();

        assertThat(started.content()).contains("run_id=child-bg");
        assertThat(stillRunning.content()).isEqualTo("still running");
        pending.complete(new RunOutcome("child-bg", "success", "background done", null, 1));
        for (int attempt = 0; attempt < 100 && events.size() < 2; attempt++) {
            Thread.sleep(10);
        }
        var completed = results.execute(
                        new ToolContext(Path.of("."), "session", "parent-run", "result"), query)
                .toCompletableFuture().get();
        var consumed = results.execute(
                        new ToolContext(Path.of("."), "session", "parent-run", "result"), query)
                .toCompletableFuture().get();

        assertThat(completed.content()).isEqualTo("background done");
        assertThat(consumed.isError()).isTrue();
        assertThat(consumed.content()).contains("Unknown run_id");
        assertThat(events).extracting(event -> event.getClass().getSimpleName())
                .containsExactly("SubagentStartedEvent", "SubagentFinishedEvent");
        registry.close();
    }

    // 功能：验证 registry shutdown 将取消传播到后台 runner 原始 completion
    // 设计：spawn 使用永久 pending runner，等待注册后 close 并观察 pending future cancelled
    @Test
    void registryCloseCancelsUnderlyingBackgroundRun() throws Exception {
        var pending = new CompletableFuture<RunOutcome>();
        var registry = new BackgroundSubagentRegistry();
        var spawn = new SpawnAgentTool(
                request -> pending,
                new EventBus(),
                registry,
                () -> "child-bg",
                name -> java.util.Optional.empty(),
                Clock.systemUTC(),
                0);
        var params = ProtocolJson.mapper().createObjectNode()
                .put("description", "background task")
                .put("prompt", "wait")
                .put("run_in_background", true);
        spawn.execute(
                        new ToolContext(Path.of("."), "session", "parent-run", "call"), params)
                .toCompletableFuture().get();

        registry.close();

        assertThat(pending).isCancelled();
        assertThat(registry.activeCount()).isZero();
    }

    // 功能：验证深度达到二时拒绝继续派生且 runner 完全不执行
    // 设计：注入记录调用的 fake runner，通过标准工具结果和标志观察深度边界
    @Test
    void rejectsSpawnAtMaximumDepth() throws Exception {
        var invoked = new AtomicBoolean();
        var tool = new SpawnAgentTool(
                request -> {
                    invoked.set(true);
                    return CompletableFuture.completedFuture(new RunOutcome(
                            request.runId(), "success", "unexpected", null, 1));
                },
                new EventBus(),
                new BackgroundSubagentRegistry(),
                () -> "child-too-deep",
                name -> java.util.Optional.empty(),
                Clock.systemUTC(),
                2);
        var params = ProtocolJson.mapper().createObjectNode()
                .put("description", "too deep")
                .put("prompt", "must not run");

        var result = tool.execute(
                        new ToolContext(Path.of("."), "session", "parent-run", "call"), params)
                .toCompletableFuture().get();

        assertThat(result.isError()).isTrue();
        assertThat(result.content()).contains("nesting limit (2)");
        assertThat(invoked).isFalse();
    }
}
