package io.klaude.mcp;

import java.util.List;
import java.util.concurrent.CompletionStage;

public interface McpConnection extends McpToolCaller, AutoCloseable {
    // 列出当前 MCP server 的远端工具定义
    CompletionStage<List<McpToolDefinition>> listTools();

    // 关闭连接及其拥有的资源
    @Override
    void close();
}
