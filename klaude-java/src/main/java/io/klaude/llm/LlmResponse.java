package io.klaude.llm;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;

public record LlmResponse(
        LlmStopReason stopReason,
        List<LlmToolCall> toolCalls,
        String text,
        LlmUsage usage,
        List<ObjectNode> thinkingBlocks) {
    // 校验响应并防御性复制动态 block
    public LlmResponse {
        java.util.Objects.requireNonNull(stopReason, "stopReason");
        toolCalls = List.copyOf(toolCalls);
        text = java.util.Objects.requireNonNull(text, "text");
        thinkingBlocks = thinkingBlocks.stream().map(ObjectNode::deepCopy).toList();
    }

    // 返回 thinking blocks 的防御性副本
    @Override
    public List<ObjectNode> thinkingBlocks() {
        return thinkingBlocks.stream().map(ObjectNode::deepCopy).toList();
    }
}
