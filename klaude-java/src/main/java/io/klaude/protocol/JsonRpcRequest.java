package io.klaude.protocol;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import java.util.Objects;

@JsonDeserialize(using = JsonRpcRequestDeserializer.class)
public record JsonRpcRequest(
        @JsonProperty(defaultValue = "2.0") String jsonrpc,
        @JsonProperty(required = true) String id,
        @JsonProperty(required = true) String method,
        ObjectNode params) {

    // 校验 JSON-RPC 请求字段并填充缺省值
    public JsonRpcRequest {
        jsonrpc = jsonrpc == null ? "2.0" : jsonrpc;
        if (!"2.0".equals(jsonrpc)) {
            throw new IllegalArgumentException("jsonrpc must be 2.0");
        }
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(method, "method");
        params = params == null ? JsonNodeFactory.instance.objectNode() : params;
    }
}
