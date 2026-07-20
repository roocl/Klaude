package io.klaude.session;

import io.klaude.protocol.SessionMode;
import io.klaude.protocol.SessionStatus;
import java.util.List;

public record Session(
        String id,
        SessionMode mode,
        SessionStatus status,
        String title,
        String createdAt,
        String updatedAt,
        List<String> runIds) {
    // 防御性复制 run ID，保持 session metadata 不可变
    public Session {
        runIds = List.copyOf(runIds);
    }
}
