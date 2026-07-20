package io.klaude.llm;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;

public record LlmRequest(
        String runId,
        int step,
        List<ObjectNode> messages,
        List<ObjectNode> toolSchemas,
        String systemPrompt) {
    // 校验请求并防御性复制消息与 schema
    public LlmRequest {
        java.util.Objects.requireNonNull(runId, "runId");
        if (step < 1) {
            throw new IllegalArgumentException("step must be positive");
        }
        messages = messages.stream().map(ObjectNode::deepCopy).toList();
        toolSchemas = toolSchemas.stream().map(ObjectNode::deepCopy).toList();
    }

    // 返回消息的防御性副本
    @Override
    public List<ObjectNode> messages() {
        return messages.stream().map(ObjectNode::deepCopy).toList();
    }

    // 返回工具 schema 的防御性副本
    @Override
    public List<ObjectNode> toolSchemas() {
        return toolSchemas.stream().map(ObjectNode::deepCopy).toList();
    }
}
