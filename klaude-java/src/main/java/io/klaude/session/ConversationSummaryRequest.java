package io.klaude.session;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Objects;

public record ConversationSummaryRequest(
        String sessionId,
        List<ObjectNode> messages,
        String focus,
        int originalTokenEstimate) {
    // 校验摘要请求并防御性复制动态消息
    public ConversationSummaryRequest {
        Objects.requireNonNull(sessionId, "sessionId");
        messages = Objects.requireNonNull(messages, "messages").stream()
                .map(ObjectNode::deepCopy)
                .toList();
        Objects.requireNonNull(focus, "focus");
        if (originalTokenEstimate < 0) {
            throw new IllegalArgumentException("originalTokenEstimate must not be negative");
        }
    }

    // 返回与内部状态隔离的摘要消息输入
    @Override
    public List<ObjectNode> messages() {
        return messages.stream().map(ObjectNode::deepCopy).toList();
    }
}
