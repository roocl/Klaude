package io.klaude.protocol;

public record RunStartedEvent(String runId, String goal, String ts) implements Event {
    // 校验运行开始事件字段
    public RunStartedEvent {
        ProtocolChecks.required(runId, "runId");
        ProtocolChecks.required(goal, "goal");
        ProtocolChecks.required(ts, "ts");
    }
}
