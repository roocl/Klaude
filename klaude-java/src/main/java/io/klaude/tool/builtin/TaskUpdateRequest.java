package io.klaude.tool.builtin;

import java.util.List;

public record TaskUpdateRequest(
        int taskId,
        String status,
        List<Integer> addBlockedBy,
        List<Integer> removeBlockedBy) {
    // 防御性复制依赖列表以保持 request 不可变
    public TaskUpdateRequest {
        addBlockedBy = List.copyOf(addBlockedBy);
        removeBlockedBy = List.copyOf(removeBlockedBy);
    }
}
