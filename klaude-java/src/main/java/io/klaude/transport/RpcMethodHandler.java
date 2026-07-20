package io.klaude.transport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.concurrent.CompletionStage;

@FunctionalInterface
public interface RpcMethodHandler {
    // 异步处理已通过 JSON-RPC envelope 校验的方法参数
    CompletionStage<JsonNode> handle(NdjsonConnection connection, ObjectNode params) throws Exception;
}
