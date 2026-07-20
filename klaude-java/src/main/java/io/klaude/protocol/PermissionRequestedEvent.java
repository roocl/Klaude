package io.klaude.protocol;

import com.fasterxml.jackson.databind.node.ObjectNode;

public record PermissionRequestedEvent(
        String runId,
        String toolUseId,
        String toolName,
        ObjectNode params,
        String paramPreview,
        String sessionId,
        String ts) implements Event {

    // 校验权限请求事件字段
    public PermissionRequestedEvent {
        ProtocolChecks.required(runId, "runId");
        ProtocolChecks.required(toolUseId, "toolUseId");
        ProtocolChecks.required(toolName, "toolName");
        ProtocolChecks.required(params, "params");
        ProtocolChecks.required(paramPreview, "paramPreview");
        ProtocolChecks.required(sessionId, "sessionId");
        ProtocolChecks.required(ts, "ts");
    }
}
