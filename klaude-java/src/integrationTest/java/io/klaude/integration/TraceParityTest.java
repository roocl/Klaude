package io.klaude.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.klaude.agent.AgentRunner;
import io.klaude.agent.event.EventBus;
import io.klaude.llm.LlmResponse;
import io.klaude.llm.LlmStopReason;
import io.klaude.llm.ScriptedLlmProvider;
import io.klaude.protocol.Event;
import io.klaude.protocol.ProtocolJson;
import io.klaude.tool.ToolRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

final class TraceParityTest {
    // 功能：验证 fixed fake run 符合冻结的 Agent Loop 契约
    // 设计：运行 scripted end_turn，归一化动态字段后比较 fixture 的事件、状态与结果
    @Test
    void normalizedTraceMatchesContractFixture() throws Exception {
        Path fixture = Path.of(System.getProperty("klaude.contractRoot"))
                .resolve("fixtures/agent-loop.json");
        var expected = ProtocolJson.mapper().readTree(Files.readString(fixture)).get(0);
        var events = new CopyOnWriteArrayList<Event>();
        var bus = new EventBus();
        bus.subscribe(event -> {
            events.add(event);
            return CompletableFuture.completedFuture(null);
        });
        var runner = new AgentRunner(
                new ScriptedLlmProvider(List.of(new LlmResponse(
                        LlmStopReason.END_TURN, List.of(), "done", null, List.of()))),
                new ToolRegistry(),
                call -> CompletableFuture.completedFuture(
                        io.klaude.tool.ToolResult.success("unused")),
                bus,
                Clock.fixed(Instant.parse("2026-07-19T12:00:00Z"), ZoneOffset.UTC),
                () -> "java-run",
                1);

        var outcome = runner.run("contract goal").toCompletableFuture().get();
        List<String> stepEvents = events.stream()
                .map(event -> ProtocolJson.mapper().valueToTree(event).path("type").asText())
                .filter(type -> type.startsWith("step."))
                .toList();

        assertThat(stepEvents).containsExactlyElementsOf(
                ProtocolJson.mapper().convertValue(
                        expected.path("event_types"),
                        ProtocolJson.mapper().getTypeFactory()
                                .constructCollectionType(List.class, String.class)));
        assertThat(outcome.status()).isEqualTo(expected.path("status").asText());
        assertThat(outcome.result()).isEqualTo(expected.path("result").asText());
        assertThat(outcome.reason()).isNull();
        assertThat(outcome.steps()).isEqualTo(expected.path("step").asInt());
    }
}
