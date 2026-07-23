package io.klaude.cli.client;

import com.fasterxml.jackson.databind.JsonNode;

public final class RpcException extends RuntimeException {
    private final int code;
    private final JsonNode data;

    // 保存 daemon 返回的 JSON-RPC 错误信息
    public RpcException(int code, String message, JsonNode data) {
        super(message);
        this.code = code;
        this.data = data;
    }

    // 返回 JSON-RPC 错误码
    public int code() {
        return code;
    }

    // 返回可选错误数据
    public JsonNode data() {
        return data;
    }
}
