package io.klaude.agent;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Objects;

public record AgentRunRequest(
        String runId,
        String goal,
        List<ObjectNode> prefillMessages,
        String sessionNotes,
        String globalContext,
        String projectContext,
        String systemPromptOverride) {
    // 校验捕获式 run 输入并防御性复制预填消息
    public AgentRunRequest {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(goal, "goal");
        prefillMessages = Objects.requireNonNull(prefillMessages, "prefillMessages").stream()
                .map(ObjectNode::deepCopy)
                .toList();
        Objects.requireNonNull(sessionNotes, "sessionNotes");
        Objects.requireNonNull(globalContext, "globalContext");
        Objects.requireNonNull(projectContext, "projectContext");
    }

    // 返回与内部状态隔离的预填消息列表
    @Override
    public List<ObjectNode> prefillMessages() {
        return prefillMessages.stream().map(ObjectNode::deepCopy).toList();
    }
}
