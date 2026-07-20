package io.klaude.mcp;

import java.util.concurrent.CompletionStage;

@FunctionalInterface
public interface McpConnector {
    // 根据 server spec 建立并完成握手
    CompletionStage<McpConnection> connect(McpServerSpec spec);
}
