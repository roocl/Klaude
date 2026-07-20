package io.klaude.protocol;

import java.util.Objects;

public record SessionGetHistoryCommand(String sessionId) implements Command {
    // 校验历史查询的 session 标识
    public SessionGetHistoryCommand {
        Objects.requireNonNull(sessionId, "sessionId");
    }
}
