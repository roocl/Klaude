package io.klaude.protocol;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Objects;

public record JsonRpcError(
        @JsonProperty(defaultValue = "2.0") String jsonrpc,
        String id,
        @JsonProperty(required = true) JsonRpcErrorObject error) {

    // 校验错误响应并填充协议版本
    public JsonRpcError {
        jsonrpc = jsonrpc == null ? "2.0" : jsonrpc;
        if (!"2.0".equals(jsonrpc)) {
            throw new IllegalArgumentException("jsonrpc must be 2.0");
        }
        Objects.requireNonNull(error, "error");
    }
}
