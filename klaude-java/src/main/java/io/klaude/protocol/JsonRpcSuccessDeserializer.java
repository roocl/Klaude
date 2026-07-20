package io.klaude.protocol;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import java.io.IOException;

final class JsonRpcSuccessDeserializer extends StdDeserializer<JsonRpcSuccess> {
    // 初始化成功响应反序列化器
    JsonRpcSuccessDeserializer() {
        super(JsonRpcSuccess.class);
    }

    // 区分缺失 result 与显式 JSON null
    @Override
    public JsonRpcSuccess deserialize(JsonParser parser, DeserializationContext context)
            throws IOException {
        JsonNode root = parser.getCodec().readTree(parser);
        if (!root.isObject()) {
            throw JsonMappingException.from(parser, "success response must be an object");
        }
        String jsonrpc = root.has("jsonrpc")
                ? EnvelopeFields.requiredText(parser, root, "jsonrpc")
                : "2.0";
        String id = EnvelopeFields.requiredText(parser, root, "id");
        if (!root.has("result")) {
            throw JsonMappingException.from(parser, "result is required");
        }
        JsonNode resultNode = root.get("result");
        JsonNode result = resultNode == null || resultNode.isNull() ? null : resultNode;
        return new JsonRpcSuccess(jsonrpc, id, result);
    }
}
