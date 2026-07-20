package io.klaude.mcp;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Objects;

public record McpToolDefinition(String name, String description, ObjectNode inputSchema) {
    // 校验 MCP 工具定义并防御性复制 schema
    public McpToolDefinition {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(description, "description");
        inputSchema = Objects.requireNonNull(inputSchema, "inputSchema").deepCopy();
    }

    // 返回与内部状态隔离的 input schema
    @Override
    public ObjectNode inputSchema() {
        return inputSchema.deepCopy();
    }
}
