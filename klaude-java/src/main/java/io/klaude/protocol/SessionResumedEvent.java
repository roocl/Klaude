package io.klaude.protocol;

public record SessionResumedEvent(String sessionId, String ts) implements Event {
    // 校验会话恢复事件字段
    public SessionResumedEvent {
        ProtocolChecks.required(sessionId, "sessionId");
        ProtocolChecks.required(ts, "ts");
    }
}
