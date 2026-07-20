package io.klaude.tool.builtin;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;

@FunctionalInterface
public interface TaskCreateService {
    // 创建任务并返回用于工具输出的 JSON
    ObjectNode create(String subject, String description, List<Integer> blockedBy) throws Exception;
}
