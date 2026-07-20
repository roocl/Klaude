package io.klaude.transport;

import com.fasterxml.jackson.databind.JsonNode;

public final class RpcHandlerException extends RuntimeException {
    private final int code;
    private final JsonNode data;

    // 创建携带 JSON-RPC code、message 与可选 data 的 handler 异常
    public RpcHandlerException(int code, String message, JsonNode data) {
        super(message);
        this.code = code;
        this.data = data;
    }

    // 返回 JSON-RPC error code
    public int code() {
        return code;
    }

    // 返回可选 JSON-RPC error data
    public JsonNode data() {
        return data;
    }
}
