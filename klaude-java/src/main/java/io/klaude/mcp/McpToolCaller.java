package io.klaude.mcp;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.concurrent.CompletionStage;

@FunctionalInterface
public interface McpToolCaller {
    // 调用远端 MCP 工具并返回拼接文本
    CompletionStage<String> callTool(String name, ObjectNode arguments);
}
