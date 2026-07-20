package io.klaude.protocol;

public record SessionMessageReceivedEvent(String sessionId, String content, String ts)
        implements Event {

    // 校验会话消息事件字段
    public SessionMessageReceivedEvent {
        ProtocolChecks.required(sessionId, "sessionId");
        ProtocolChecks.required(content, "content");
        ProtocolChecks.required(ts, "ts");
    }
}
