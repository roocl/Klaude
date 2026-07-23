package io.klaude.protocol;

import java.util.List;
import java.util.Objects;

public record SessionListResult(List<SessionInfo> sessions) {
    // 创建不可变 session 列表
    public SessionListResult {
        sessions = List.copyOf(Objects.requireNonNull(sessions, "sessions"));
    }
}
