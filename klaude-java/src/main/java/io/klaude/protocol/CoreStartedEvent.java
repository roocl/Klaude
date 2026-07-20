package io.klaude.protocol;

public record CoreStartedEvent(String listenAddr, String version) implements Event {
    // 校验核心启动事件字段
    public CoreStartedEvent {
        ProtocolChecks.required(listenAddr, "listenAddr");
        ProtocolChecks.required(version, "version");
    }
}
