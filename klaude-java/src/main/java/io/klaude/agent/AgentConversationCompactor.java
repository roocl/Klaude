package io.klaude.agent;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.klaude.agent.event.EventBus;
import io.klaude.llm.LlmProvider;
import io.klaude.llm.LlmRequest;
import io.klaude.protocol.ContextCompactedEvent;
import io.klaude.protocol.ProtocolJson;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public final class AgentConversationCompactor implements AgentCompactor {
    private static final DateTimeFormatter FILE_TIME =
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss").withZone(ZoneOffset.UTC);
    private static final String ACKNOWLEDGEMENT =
            "Understood, I'll continue from this summary.";
    private final LlmProvider provider;
    private final EventBus events;
    private final Path sessionDirectory;
    private final String sessionId;
    private final Clock clock;

    // 初始化 provider、事件、session 路径与时间边界
    public AgentConversationCompactor(
            LlmProvider provider,
            EventBus events,
            Path sessionDirectory,
            String sessionId,
            Clock clock) {
        this.provider = java.util.Objects.requireNonNull(provider, "provider");
        this.events = java.util.Objects.requireNonNull(events, "events");
        this.sessionDirectory = java.util.Objects.requireNonNull(
                sessionDirectory, "sessionDirectory");
        this.sessionId = java.util.Objects.requireNonNull(sessionId, "sessionId");
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
    }

    // 生成摘要并在成功时替换 context、写摘要文件和发布事件
    @Override
    public CompletionStage<Void> compact(ExecutionContext context) {
        List<ObjectNode> original = context.messages();
        int originalTokens = estimateTokens(original);
        ObjectNode prompt = ProtocolJson.mapper().createObjectNode()
                .put("role", "user")
                .put("content", summaryPrompt(original));
        CompletionStage<io.klaude.llm.LlmResponse> response;
        try {
            response = provider.chat(
                    new LlmRequest(
                            "compact", 1, List.of(prompt), List.of(),
                            "You are a helpful assistant that summarizes conversations."),
                    event -> CompletableFuture.completedFuture(null));
        } catch (Throwable error) {
            return CompletableFuture.completedFuture(null);
        }
        return response.handle((value, error) -> error == null ? value : null)
                .thenCompose(value -> {
                    if (value == null || value.text().isBlank()) {
                        return CompletableFuture.completedFuture(null);
                    }
                    String summary = value.text().strip();
                    ObjectNode summaryMessage = ProtocolJson.mapper().createObjectNode()
                            .put("role", "user")
                            .put("content", summary);
                    ObjectNode acknowledgement = ProtocolJson.mapper().createObjectNode()
                            .put("role", "assistant")
                            .put("content", ACKNOWLEDGEMENT);
                    context.replaceMessages(List.of(summaryMessage, acknowledgement));
                    writeSummary(summary);
                    int summaryTokens = value.usage() == null
                            ? estimateTextTokens(summary)
                            : value.usage().outputTokens();
                    return events.publish(new ContextCompactedEvent(
                            sessionId,
                            context.runId(),
                            originalTokens,
                            summaryTokens,
                            Instant.now(clock).toString()));
                });
    }

    // 将消息历史序列化为可供摘要 provider 阅读的文本
    private static String summaryPrompt(List<ObjectNode> messages) {
        StringBuilder text = new StringBuilder(
                "You are compressing an agent conversation into a handoff summary.\n"
                        + "Preserve the original goal, completed work, constraints, file state, TODOs, and critical data.\n\n---\n\n");
        for (ObjectNode message : messages) {
            text.append('[')
                    .append(message.path("role").asText("unknown").toUpperCase(java.util.Locale.ROOT))
                    .append("]\n")
                    .append(message.path("content").isTextual()
                            ? message.path("content").asText()
                            : message.path("content").toString())
                    .append("\n\n");
        }
        return text.toString();
    }

    // 尽力将摘要以 UTF-8 写入确定时间命名的 session 文件
    private void writeSummary(String summary) {
        try {
            Files.createDirectories(sessionDirectory);
            Files.writeString(
                    sessionDirectory.resolve(
                            "summary_" + FILE_TIME.format(Instant.now(clock)) + ".md"),
                    summary,
                    StandardCharsets.UTF_8);
        } catch (java.io.IOException ignored) {
            // Summary file is supplementary; the in-memory compact result remains usable.
        }
    }

    // 使用消息 content Unicode 字符数除以四估算 token
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

    // 使用 Unicode 字符数除以四估算单段文本 token
    private static int estimateTextTokens(String text) {
        return text.codePointCount(0, text.length()) / 4;
    }
}
