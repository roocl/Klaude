package io.klaude.transport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.klaude.protocol.ProtocolJson;
import java.time.Clock;
import java.time.Instant;
import java.util.function.Consumer;

final class TransportTrace {
    // 禁止实例化 transport trace 工具类
    private TransportTrace() {
    }

    // 构造一个完整 IPC trace record
    static ObjectNode record(
            Clock clock,
            String direction,
            String kind,
            NdjsonConnection connection,
            String runId,
            JsonNode data) {
        ObjectNode trace = ProtocolJson.mapper().createObjectNode();
        trace.put("ts", Instant.now(clock).toString());
        trace.put("direction", direction);
        trace.put("layer", "ipc");
        trace.put("kind", kind);
        if (runId == null) {
            trace.putNull("run_id");
        } else {
            trace.put("run_id", runId);
        }
        trace.putNull("step");
        trace.put("client_id", connection.id());
        trace.set("data", data);
        return trace;
    }

    // 调用 trace sink 并隔离监控路径异常
    static void emit(Consumer<ObjectNode> sink, ObjectNode trace) {
        try {
            sink.accept(trace);
        } catch (RuntimeException ignored) {
            // Trace must not break the IPC path.
        }
    }
}
