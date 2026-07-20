package io.klaude.protocol;

import java.util.Objects;

public record SessionCloseCommand(String sessionId) implements Command {
    // 校验关闭会话的 session 标识
    public SessionCloseCommand {
        Objects.requireNonNull(sessionId, "sessionId");
    }
}
