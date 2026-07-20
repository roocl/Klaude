package io.klaude.agent;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import io.klaude.protocol.ProtocolJson;
import java.util.ArrayList;
import java.util.List;

public final class ExecutionContext {
    private final String runId;
    private final String goal;
    private final int maxSteps;
    private final String sessionNotes;
    private final String globalContext;
    private final String projectContext;
    private final String systemPromptOverride;
    private final List<ObjectNode> messages = new ArrayList<>();
    private int step;
    private String status = "running";
    private String result = "";
    private String reason;

    // 初始化 run 身份、步数限制和首条 user goal 消息
    public ExecutionContext(String runId, String goal, int maxSteps) {
        this(runId, goal, maxSteps, List.of());
    }

    // 初始化 run 身份、步数限制及可选的完整预填消息历史
    public ExecutionContext(
            String runId,
            String goal,
            int maxSteps,
            List<ObjectNode> prefillMessages) {
        this(runId, goal, maxSteps, prefillMessages, "", "", "", null);
    }

    // 初始化包含预填历史与全部 system prompt 层的 execution context
    public ExecutionContext(
            String runId,
            String goal,
            int maxSteps,
            List<ObjectNode> prefillMessages,
            String sessionNotes,
            String globalContext,
            String projectContext,
            String systemPromptOverride) {
        this.runId = java.util.Objects.requireNonNull(runId, "runId");
        this.goal = java.util.Objects.requireNonNull(goal, "goal");
        java.util.Objects.requireNonNull(prefillMessages, "prefillMessages");
        this.sessionNotes = java.util.Objects.requireNonNull(sessionNotes, "sessionNotes");
        this.globalContext = java.util.Objects.requireNonNull(globalContext, "globalContext");
        this.projectContext = java.util.Objects.requireNonNull(projectContext, "projectContext");
        this.systemPromptOverride = systemPromptOverride;
        if (maxSteps < 1) {
            throw new IllegalArgumentException("maxSteps must be positive");
        }
        this.maxSteps = maxSteps;
        if (prefillMessages.isEmpty()) {
            ObjectNode initial = ProtocolJson.mapper().createObjectNode();
            initial.put("role", "user");
            initial.put("content", goal);
            messages.add(initial);
        } else {
            prefillMessages.forEach(message -> messages.add(
                    java.util.Objects.requireNonNull(message, "prefill message").deepCopy()));
        }
    }

    // 按 base、global、project、session notes 顺序组装 system prompt
    public String systemPrompt(String base) {
        StringBuilder prompt = new StringBuilder(
                systemPromptOverride == null ? java.util.Objects.requireNonNull(base, "base")
                        : systemPromptOverride);
        appendPromptSection(prompt, "Global Context", globalContext);
        appendPromptSection(prompt, "Project Context", projectContext);
        if (!sessionNotes.isBlank()) {
            appendPromptSection(prompt, "Session Notes", sessionNotes);
            prompt.append("\n\nRemember important durable facts by calling note_save.");
        }
        return prompt.toString();
    }

    // 非空时追加一个去除首尾空白的 prompt section
    private static void appendPromptSection(
            StringBuilder prompt, String heading, String content) {
        if (!content.isBlank()) {
            prompt.append("\n\n## ")
                    .append(heading)
                    .append('\n')
                    .append(content.strip());
        }
    }

    // 返回当前 run 是否已终止
    public boolean isDone() {
        return !status.equals("running");
    }

    // 返回已开始的步数
    public int step() {
        return step;
    }

    // 返回 provider 消息的防御性副本
    public List<ObjectNode> messages() {
        return messages.stream().map(ObjectNode::deepCopy).toList();
    }

    // 开始下一步并返回从 1 开始的步数
    int startStep() {
        return ++step;
    }

    // 返回 run ID
    public String runId() {
        return runId;
    }

    // 追加一条 text assistant 消息
    void addAssistantText(String text) {
        ObjectNode message = ProtocolJson.mapper().createObjectNode();
        message.put("role", "assistant");
        var content = ProtocolJson.mapper().createArrayNode();
        if (!text.isEmpty()) {
            content.add(ProtocolJson.mapper().createObjectNode()
                    .put("type", "text")
                    .put("text", text));
        }
        message.set("content", content);
        messages.add(message);
    }

    // 追加一条已排序的 assistant content blocks 消息
    void addAssistantMessage(ArrayNode content) {
        ObjectNode message = ProtocolJson.mapper().createObjectNode();
        message.put("role", "assistant");
        message.set("content", content.deepCopy());
        messages.add(message);
    }

    // 使用防御性副本原子替换全部 provider 消息
    void replaceMessages(List<ObjectNode> replacement) {
        messages.clear();
        java.util.Objects.requireNonNull(replacement, "replacement").forEach(message ->
                messages.add(java.util.Objects.requireNonNull(message, "message").deepCopy()));
    }

    // 追加工具结果并将同步多个结果合并为一条 user 消息
    void addToolResult(String toolUseId, String content, boolean isError) {
        ObjectNode block = ProtocolJson.mapper().createObjectNode();
        block.put("type", "tool_result");
        block.put("tool_use_id", toolUseId);
        block.put("content", content);
        if (isError) {
            block.put("is_error", true);
        }
        ObjectNode last = messages.isEmpty() ? null : messages.getLast();
        if (last != null
                && last.path("role").asText().equals("user")
                && last.path("content").isArray()
                && !last.path("content").isEmpty()
                && java.util.stream.StreamSupport.stream(
                                last.path("content").spliterator(), false)
                        .allMatch(value -> value.path("type").asText().equals("tool_result"))) {
            ((ArrayNode) last.path("content")).add(block);
            return;
        }
        ObjectNode message = ProtocolJson.mapper().createObjectNode();
        message.put("role", "user");
        message.set("content", ProtocolJson.mapper().createArrayNode().add(block));
        messages.add(message);
    }

    // 将 run 标记为成功并保存最终文本
    void markSuccess(String value) {
        status = "success";
        result = value;
    }

    // 将 run 标记为失败并保存原因
    void markFailed(String value) {
        status = "failed";
        reason = java.util.Objects.requireNonNull(value, "reason");
    }

    // 返回 run 的最大步数
    int maxSteps() {
        return maxSteps;
    }

    // 返回 run 状态 wire value
    public String status() {
        return status;
    }

    // 返回 run 最终文本结果
    public String result() {
        return result;
    }

    // 返回 run 失败原因或 null
    public String reason() {
        return reason;
    }
}
