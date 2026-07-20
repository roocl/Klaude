package io.klaude.protocol;

public record LogLineEvent(
        String runId,
        String level,
        String source,
        String message,
        String ts) implements Event {

    // 校验日志行事件字段
    public LogLineEvent {
        ProtocolChecks.required(runId, "runId");
        ProtocolChecks.required(level, "level");
        ProtocolChecks.required(source, "source");
        ProtocolChecks.required(message, "message");
        ProtocolChecks.required(ts, "ts");
    }
}
