package io.klaude.mcp;

public class McpException extends RuntimeException {
    // 创建带上下文的 MCP 异常
    public McpException(String message) {
        super(message);
    }

    // 创建保留原始 cause 的 MCP 异常
    public McpException(String message, Throwable cause) {
        super(message, cause);
    }
}
