package io.klaude.tool;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.concurrent.CompletionStage;

public interface Tool {
    // 返回 registry 中唯一的工具名
    String name();

    // 返回提供给调用者的工具描述
    String description();

    // 返回工具参数的 JSON Schema
    ObjectNode inputSchema();

    // 异步执行已校验且已授权的工具调用
    CompletionStage<ToolResult> execute(ToolContext context, ObjectNode params);
}
