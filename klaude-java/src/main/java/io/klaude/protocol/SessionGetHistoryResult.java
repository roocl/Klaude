package io.klaude.protocol;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Objects;

public record SessionGetHistoryResult(List<ObjectNode> messages) {
    // 校验历史消息并创建不可变副本
    public SessionGetHistoryResult {
        messages = List.copyOf(Objects.requireNonNull(messages, "messages"));
    }
}
