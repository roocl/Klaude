package io.klaude.protocol;

public record SessionClosedEvent(String sessionId, String ts) implements Event {
    // 校验会话关闭事件字段
    public SessionClosedEvent {
        ProtocolChecks.required(sessionId, "sessionId");
        ProtocolChecks.required(ts, "ts");
    }
}
