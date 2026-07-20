package io.klaude.protocol;

public record PermissionGrantedEvent(
        String runId,
        String toolUseId,
        String decision,
        String ts) implements Event {

    // 校验权限授予事件字段
    public PermissionGrantedEvent {
        ProtocolChecks.required(runId, "runId");
        ProtocolChecks.required(toolUseId, "toolUseId");
        ProtocolChecks.required(decision, "decision");
        ProtocolChecks.required(ts, "ts");
    }
}
