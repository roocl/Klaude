package io.klaude.protocol;

import com.fasterxml.jackson.databind.node.ObjectNode;

public record ToolCallStartedEvent(
        String runId,
        String toolUseId,
        String toolName,
        ObjectNode params,
        String ts) implements Event {

    // 校验工具调用开始事件字段
    public ToolCallStartedEvent {
        ProtocolChecks.required(runId, "runId");
        ProtocolChecks.required(toolUseId, "toolUseId");
        ProtocolChecks.required(toolName, "toolName");
        ProtocolChecks.required(params, "params");
        ProtocolChecks.required(ts, "ts");
    }
}
