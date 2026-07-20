package io.klaude.mcp;

public final class McpToolException extends McpException {
    private final int code;

    // 创建保留 JSON-RPC application error code 的工具异常
    public McpToolException(int code, String message) {
        super(message + " (code=" + code + ")");
        this.code = code;
    }

    // 返回 MCP JSON-RPC error code
    public int code() {
        return code;
    }
}
