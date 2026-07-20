package io.klaude.agent;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Objects;

public record AgentRunCapture(RunOutcome outcome, List<ObjectNode> newMessages) {
    // 校验 run capture 并防御性复制新增消息
    public AgentRunCapture {
        Objects.requireNonNull(outcome, "outcome");
        newMessages = Objects.requireNonNull(newMessages, "newMessages").stream()
                .map(ObjectNode::deepCopy)
                .toList();
    }

    // 返回与内部状态隔离的新增消息列表
    @Override
    public List<ObjectNode> newMessages() {
        return newMessages.stream().map(ObjectNode::deepCopy).toList();
    }
}
