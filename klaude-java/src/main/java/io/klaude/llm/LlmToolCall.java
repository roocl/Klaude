package io.klaude.llm;

import com.fasterxml.jackson.databind.node.ObjectNode;

public record LlmToolCall(String id, String name, ObjectNode input) {
    // 校验工具调用并保存输入防御性副本
    public LlmToolCall {
        java.util.Objects.requireNonNull(id, "id");
        java.util.Objects.requireNonNull(name, "name");
        input = java.util.Objects.requireNonNull(input, "input").deepCopy();
    }

    // 返回工具输入的防御性副本
    @Override
    public ObjectNode input() {
        return input.deepCopy();
    }
}
