package io.klaude.tool.builtin;

@FunctionalInterface
public interface TaskListService {
    // 生成当前任务列表的稳定文本摘要
    String list() throws Exception;
}
