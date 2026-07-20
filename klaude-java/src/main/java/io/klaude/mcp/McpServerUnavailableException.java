package io.klaude.mcp;

public final class McpServerUnavailableException extends McpException {
    // 创建连接不可用异常
    public McpServerUnavailableException(String message) {
        super(message);
    }

    // 创建保留底层 I/O cause 的连接不可用异常
    public McpServerUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
