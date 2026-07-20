package io.klaude.tool.builtin;

import com.fasterxml.jackson.databind.node.ObjectNode;

@FunctionalInterface
public interface TaskGetService {
    // 按整数 ID 查询任务并返回 JSON
    ObjectNode get(int taskId) throws Exception;
}
