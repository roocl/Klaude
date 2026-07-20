package io.klaude.protocol;

import java.util.Objects;

public record SessionCloseResult(SessionStatus status) {
    // 校验关闭会话结果状态
    public SessionCloseResult {
        Objects.requireNonNull(status, "status");
    }
}
