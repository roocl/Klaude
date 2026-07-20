package io.klaude.protocol;

import java.util.Objects;

public record SessionCreateResult(String sessionId, SessionStatus status) {
    // 校验创建会话结果字段
    public SessionCreateResult {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(status, "status");
    }
}
