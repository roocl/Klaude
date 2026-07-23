package io.klaude.protocol;

import java.util.Objects;

public record SessionInfo(
        String sessionId,
        SessionMode mode,
        SessionStatus status,
        String title,
        String updatedAt,
        String lastRunId) {
    // 校验 session 列表项并允许缺少最近 run
    public SessionInfo {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(updatedAt, "updatedAt");
        lastRunId = lastRunId == null ? "" : lastRunId;
    }
}
