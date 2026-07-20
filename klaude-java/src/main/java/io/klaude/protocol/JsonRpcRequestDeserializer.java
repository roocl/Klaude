package io.klaude.protocol;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;

final class JsonRpcRequestDeserializer extends StdDeserializer<JsonRpcRequest> {
    // 初始化请求反序列化器
    JsonRpcRequestDeserializer() {
        super(JsonRpcRequest.class);
    }

    // 区分缺失字段与显式 null 并构造请求
    @Override
    public JsonRpcRequest deserialize(JsonParser parser, DeserializationContext context)
            throws IOException {
        JsonNode root = parser.getCodec().readTree(parser);
        if (!root.isObject()) {
            throw JsonMappingException.from(parser, "request must be an object");
        }
        String jsonrpc = root.has("jsonrpc")
                ? EnvelopeFields.requiredText(parser, root, "jsonrpc")
                : "2.0";
        String id = EnvelopeFields.requiredText(parser, root, "id");
        String method = EnvelopeFields.requiredText(parser, root, "method");
        ObjectNode params = JsonNodeFactory.instance.objectNode();
        if (root.has("params")) {
            JsonNode paramsNode = root.get("params");
            if (paramsNode == null || !paramsNode.isObject()) {
                throw JsonMappingException.from(parser, "params must be an object");
            }
            params = (ObjectNode) paramsNode;
        }
        return new JsonRpcRequest(jsonrpc, id, method, params);
    }

}
