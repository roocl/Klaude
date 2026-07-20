package io.klaude.protocol;

import java.util.Objects;

public record SessionCompactCommand(String sessionId, String focus) implements Command {
    // 校验压缩会话命令并填充 focus 默认值
    public SessionCompactCommand {
        Objects.requireNonNull(sessionId, "sessionId");
        focus = focus == null ? "" : focus;
    }
}
