package io.klaude.protocol;

public record ToolCallFailedEvent(
        String runId,
        String toolUseId,
        String toolName,
        String errorClass,
        String errorMessage,
        Integer elapsedMs,
        Integer attempt,
        String ts) implements Event {

    // 校验工具调用失败事件并填充重试次数
    public ToolCallFailedEvent {
        ProtocolChecks.required(runId, "runId");
        ProtocolChecks.required(toolUseId, "toolUseId");
        ProtocolChecks.required(toolName, "toolName");
        ProtocolChecks.required(errorClass, "errorClass");
        ProtocolChecks.required(errorMessage, "errorMessage");
        ProtocolChecks.required(elapsedMs, "elapsedMs");
        attempt = attempt == null ? 1 : attempt;
        ProtocolChecks.required(ts, "ts");
    }
}
