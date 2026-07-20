package io.klaude.protocol;

public record SessionCreatedEvent(String sessionId, String mode, String ts) implements Event {
    // 校验会话创建事件字段
    public SessionCreatedEvent {
        ProtocolChecks.required(sessionId, "sessionId");
        ProtocolChecks.required(mode, "mode");
        ProtocolChecks.required(ts, "ts");
    }
}
