package io.klaude.protocol;

public record SessionWaitingForInputEvent(String sessionId, String lastRunId, String ts)
        implements Event {

    // 校验会话等待输入事件字段
    public SessionWaitingForInputEvent {
        ProtocolChecks.required(sessionId, "sessionId");
        ProtocolChecks.required(lastRunId, "lastRunId");
        ProtocolChecks.required(ts, "ts");
    }
}
