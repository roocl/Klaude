package io.klaude.protocol;

public record StepStartedEvent(String runId, Integer step, String ts) implements Event {
    // 校验步骤开始事件字段
    public StepStartedEvent {
        ProtocolChecks.required(runId, "runId");
        ProtocolChecks.required(step, "step");
        ProtocolChecks.required(ts, "ts");
    }
}
