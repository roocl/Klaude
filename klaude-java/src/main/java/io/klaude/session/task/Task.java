package io.klaude.session.task;

import java.util.List;

public record Task(
        int id,
        String subject,
        String description,
        TaskStatus status,
        List<Integer> blockedBy,
        String createdAt,
        String updatedAt) {
    // 防御性复制依赖 ID，保持 task metadata 不可变
    public Task {
        blockedBy = List.copyOf(blockedBy);
    }
}
