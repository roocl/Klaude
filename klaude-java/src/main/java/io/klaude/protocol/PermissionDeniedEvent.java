package io.klaude.protocol;

public record PermissionDeniedEvent(
        String runId,
        String toolUseId,
        String decision,
        String ts) implements Event {

    // 校验权限拒绝事件字段
    public PermissionDeniedEvent {
        ProtocolChecks.required(runId, "runId");
        ProtocolChecks.required(toolUseId, "toolUseId");
        ProtocolChecks.required(decision, "decision");
        ProtocolChecks.required(ts, "ts");
    }
}
