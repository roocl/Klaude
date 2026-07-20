package io.klaude.protocol;

public record SubagentFinishedEvent(
        String runId,
        String parentRunId,
        String status,
        String ts) implements Event {

    // 校验子 Agent 完成事件字段
    public SubagentFinishedEvent {
        ProtocolChecks.required(runId, "runId");
        ProtocolChecks.required(parentRunId, "parentRunId");
        ProtocolChecks.required(status, "status");
        ProtocolChecks.required(ts, "ts");
    }
}
