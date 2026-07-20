package io.klaude.tool.builtin;

import com.fasterxml.jackson.databind.node.ObjectNode;

@FunctionalInterface
public interface TaskUpdateService {
    // 更新任务并返回更新后的 JSON
    ObjectNode update(TaskUpdateRequest request) throws Exception;
}
