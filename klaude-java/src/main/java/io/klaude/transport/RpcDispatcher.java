package io.klaude.transport;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.klaude.protocol.JsonRpcError;
import io.klaude.protocol.JsonRpcErrorObject;
import io.klaude.protocol.JsonRpcRequest;
import io.klaude.protocol.JsonRpcSuccess;
import io.klaude.protocol.ProtocolJson;
import java.util.Map;
import java.time.Clock;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public final class RpcDispatcher implements NdjsonLineHandler {
    private final Map<String, RpcMethodHandler> handlers = new ConcurrentHashMap<>();
    private final Clock clock;
    private final Consumer<com.fasterxml.jackson.databind.node.ObjectNode> traceSink;

    // 创建不记录 trace 的默认 dispatcher
    public RpcDispatcher() {
        this(Clock.systemUTC(), ignored -> { });
    }

    // 创建使用注入时间和 trace sink 的 dispatcher
    public RpcDispatcher(
            Clock clock,
            Consumer<com.fasterxml.jackson.databind.node.ObjectNode> traceSink) {
        this.clock = clock;
        this.traceSink = traceSink;
    }

    // 注册或替换一个 JSON-RPC method handler
    public void register(String method, RpcMethodHandler handler) {
        handlers.put(method, handler);
    }

    // 返回请求超限错误供 transport 写回后关闭连接
    @Override
    public CompletionStage<Void> handleOversized(NdjsonConnection connection) {
        return sendError(connection, null, -32600, "Request too large", null);
    }

    // 解析一条 JSON-RPC request 并返回协议兼容错误
    @Override
    public CompletionStage<Void> handle(NdjsonConnection connection, String line) {
        JsonNode raw;
        try {
            raw = ProtocolJson.mapper().readTree(line);
        } catch (JsonProcessingException error) {
            return sendError(connection, null, -32700, "Parse error", null);
        }
        final JsonRpcRequest request;
        try {
            request = ProtocolJson.mapper().treeToValue(raw, JsonRpcRequest.class);
        } catch (Exception error) {
            return sendError(
                    connection,
                    null,
                    -32600,
                    "Invalid Request",
                    JsonNodeFactory.instance.textNode(error.getMessage()));
        }
        traceCommand(connection, request);
        RpcMethodHandler methodHandler = handlers.get(request.method());
        if (methodHandler == null) {
            return sendError(
                    connection,
                    request.id(),
                    -32601,
                    "Method not found: " + request.method(),
                    null);
        }
        try {
            CompletionStage<JsonNode> result = methodHandler.handle(connection, request.params());
            CompletableFuture<Void> completion = new CompletableFuture<>();
            result.whenComplete((value, error) -> forwardHandlerResult(
                    connection, request.id(), value, error, completion));
            return completion;
        } catch (Throwable error) {
            return sendHandlerError(connection, request.id(), error);
        }
    }

    // 将异步 handler 结果或异常转成对应 JSON-RPC envelope
    private void forwardHandlerResult(
            NdjsonConnection connection,
            String id,
            JsonNode result,
            Throwable error,
            CompletableFuture<Void> completion) {
        CompletionStage<Void> send = error == null
                ? sendSuccess(connection, id, result)
                : sendHandlerError(connection, id, error);
        send.whenComplete((ignored, sendError) -> {
            if (sendError == null) {
                completion.complete(null);
            } else {
                completion.completeExceptionally(sendError);
            }
        });
    }

    // 将 handler 异常分类为 invalid params 或 internal error
    private CompletionStage<Void> sendHandlerError(
            NdjsonConnection connection, String id, Throwable failure) {
        Throwable error = unwrap(failure);
        if (error instanceof InvalidParamsException) {
            return sendError(
                    connection,
                    id,
                    -32600,
                    "Invalid params",
                    JsonNodeFactory.instance.textNode(error.getMessage()));
        }
        if (error instanceof RpcHandlerException handlerError) {
            return sendError(
                    connection,
                    id,
                    handlerError.code(),
                    handlerError.getMessage(),
                    handlerError.data());
        }
        return sendError(connection, id, -32603, "Internal error", null);
    }

    // 解开 CompletionException 以保留业务异常分类
    private static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    // 构造并异步发送一个 JSON-RPC success envelope
    private CompletionStage<Void> sendSuccess(
            NdjsonConnection connection, String id, JsonNode result) {
        JsonRpcSuccess success = new JsonRpcSuccess("2.0", id, result);
        JsonNode wire = ProtocolJson.mapper().valueToTree(success);
        return sendAndTrace(connection, wire, "response");
    }

    // 构造并异步发送一个 JSON-RPC error envelope
    private CompletionStage<Void> sendError(
            NdjsonConnection connection,
            String id,
            int code,
            String message,
            JsonNode data) {
        JsonRpcError error = new JsonRpcError(
                "2.0", id, new JsonRpcErrorObject(code, message, data));
        JsonNode wire = ProtocolJson.mapper().valueToTree(error);
        return sendAndTrace(connection, wire, "error");
    }

    // 记录一个通过 envelope 校验的 inbound command
    private void traceCommand(NdjsonConnection connection, JsonRpcRequest request) {
        var data = ProtocolJson.mapper().createObjectNode();
        data.put("method", request.method());
        data.put("id", request.id());
        data.set("params", request.params());
        TransportTrace.emit(traceSink, TransportTrace.record(
                clock, "CLIENT→CORE", "command", connection, null, data));
    }

    // 在成功写回后记录 outbound response 或 error
    private CompletionStage<Void> sendAndTrace(
            NdjsonConnection connection, JsonNode wire, String kind) {
        return connection.send(wire).thenRun(() -> TransportTrace.emit(
                traceSink,
                TransportTrace.record(
                        clock, "CORE→CLIENT", kind, connection, null, wire)));
    }
}
