package io.klaude.protocol;

import java.util.Objects;

public record SessionSendMessageCommand(String sessionId, String content) implements Command {
    // 校验发送消息命令字段
    public SessionSendMessageCommand {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(content, "content");
    }
}
