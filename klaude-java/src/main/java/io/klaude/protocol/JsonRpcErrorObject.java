package io.klaude.protocol;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Objects;

public record JsonRpcErrorObject(
        @JsonProperty(required = true) Integer code,
        @JsonProperty(required = true) String message,
        JsonNode data) {

    // 校验 JSON-RPC 错误对象的必填字段
    public JsonRpcErrorObject {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(message, "message");
    }
}
