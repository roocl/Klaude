package io.klaude.protocol;

public record ToolCallFinishedEvent(
        String runId,
        String toolUseId,
        String toolName,
        Integer elapsedMs,
        String output,
        String ts) implements Event {

    // 校验工具调用完成事件并填充输出默认值
    public ToolCallFinishedEvent {
        ProtocolChecks.required(runId, "runId");
        ProtocolChecks.required(toolUseId, "toolUseId");
        ProtocolChecks.required(toolName, "toolName");
        ProtocolChecks.required(elapsedMs, "elapsedMs");
        output = output == null ? "" : output;
        ProtocolChecks.required(ts, "ts");
    }
}
