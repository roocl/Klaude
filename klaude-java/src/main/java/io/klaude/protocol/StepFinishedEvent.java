package io.klaude.protocol;

public record StepFinishedEvent(String runId, Integer step, String ts) implements Event {
    // 校验步骤结束事件字段
    public StepFinishedEvent {
        ProtocolChecks.required(runId, "runId");
        ProtocolChecks.required(step, "step");
        ProtocolChecks.required(ts, "ts");
    }
}
