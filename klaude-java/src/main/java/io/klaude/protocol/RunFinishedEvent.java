package io.klaude.protocol;

public record RunFinishedEvent(
        String runId,
        String status,
        String reason,
        Integer steps,
        String ts) implements Event {

    // 校验运行结束事件的非空字段
    public RunFinishedEvent {
        ProtocolChecks.required(runId, "runId");
        ProtocolChecks.required(status, "status");
        ProtocolChecks.required(steps, "steps");
        ProtocolChecks.required(ts, "ts");
    }
}
