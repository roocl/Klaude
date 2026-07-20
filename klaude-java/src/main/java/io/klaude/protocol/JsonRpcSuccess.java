package io.klaude.protocol;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import java.util.Objects;

@JsonDeserialize(using = JsonRpcSuccessDeserializer.class)
public record JsonRpcSuccess(
        @JsonProperty(defaultValue = "2.0") String jsonrpc,
        @JsonProperty(required = true) String id,
        @JsonProperty(required = true) JsonNode result) {

    // 校验成功响应字段并填充协议版本
    public JsonRpcSuccess {
        jsonrpc = jsonrpc == null ? "2.0" : jsonrpc;
        if (!"2.0".equals(jsonrpc)) {
            throw new IllegalArgumentException("jsonrpc must be 2.0");
        }
        Objects.requireNonNull(id, "id");
    }
}
