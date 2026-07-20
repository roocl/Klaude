package io.klaude.tool;

import com.fasterxml.jackson.databind.node.ObjectNode;

public record ToolDefinition(String name, String description, ObjectNode inputSchema) {
    // 校验 definition 并保存 schema 防御性副本
    public ToolDefinition {
        java.util.Objects.requireNonNull(name, "name");
        java.util.Objects.requireNonNull(description, "description");
        inputSchema = java.util.Objects.requireNonNull(inputSchema, "inputSchema").deepCopy();
    }

    // 返回 schema 防御性副本
    @Override
    public ObjectNode inputSchema() {
        return inputSchema.deepCopy();
    }
}
