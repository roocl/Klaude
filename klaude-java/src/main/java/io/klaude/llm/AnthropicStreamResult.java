package io.klaude.llm;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;

public record AnthropicStreamResult(
        LlmStopReason stopReason,
        List<LlmToolCall> toolCalls,
        List<ObjectNode> thinkingBlocks,
        LlmUsage usage) {
    // 校验流结果并防御性复制动态 blocks
    public AnthropicStreamResult {
        java.util.Objects.requireNonNull(stopReason, "stopReason");
        toolCalls = List.copyOf(toolCalls);
        thinkingBlocks = thinkingBlocks.stream().map(ObjectNode::deepCopy).toList();
        java.util.Objects.requireNonNull(usage, "usage");
    }

    // 返回 thinking blocks 的防御性副本
    @Override
    public List<ObjectNode> thinkingBlocks() {
        return thinkingBlocks.stream().map(ObjectNode::deepCopy).toList();
    }
}
