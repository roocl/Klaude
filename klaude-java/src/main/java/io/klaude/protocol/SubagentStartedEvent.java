package io.klaude.protocol;

public record SubagentStartedEvent(
        String runId,
        String parentRunId,
        String description,
        String ts) implements Event {

    // 校验子 Agent 启动事件字段
    public SubagentStartedEvent {
        ProtocolChecks.required(runId, "runId");
        ProtocolChecks.required(parentRunId, "parentRunId");
        ProtocolChecks.required(description, "description");
        ProtocolChecks.required(ts, "ts");
    }
}
