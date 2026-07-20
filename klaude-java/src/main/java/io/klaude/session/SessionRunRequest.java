package io.klaude.session;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.klaude.protocol.SessionMode;
import java.util.List;
import java.util.Objects;

public record SessionRunRequest(
        String sessionId,
        SessionMode mode,
        String runId,
        String goal,
        List<ObjectNode> history,
        String notes,
        String systemPromptOverride,
        List<String> allowedTools) {
    // 校验 session turn 字段并防御性复制动态消息历史
    public SessionRunRequest {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(goal, "goal");
        history = Objects.requireNonNull(history, "history").stream()
                .map(ObjectNode::deepCopy)
                .toList();
        Objects.requireNonNull(notes, "notes");
        allowedTools = List.copyOf(Objects.requireNonNull(allowedTools, "allowedTools"));
    }

    // 返回与内部状态隔离的模型消息历史
    @Override
    public List<ObjectNode> history() {
        return history.stream().map(ObjectNode::deepCopy).toList();
    }
}
