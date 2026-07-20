package io.klaude.daemon;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.klaude.protocol.ProtocolJson;
import io.klaude.session.SessionStore;
import io.klaude.session.task.Task;
import io.klaude.session.task.TaskManager;
import io.klaude.session.task.TaskStatus;
import io.klaude.tool.ToolRegistry;
import io.klaude.tool.Tool;
import io.klaude.tool.builtin.BashTool;
import io.klaude.tool.builtin.ListDirectoryTool;
import io.klaude.tool.builtin.NoteSaveTool;
import io.klaude.tool.builtin.ReadFileTool;
import io.klaude.tool.builtin.TaskCreateTool;
import io.klaude.tool.builtin.TaskGetTool;
import io.klaude.tool.builtin.TaskListTool;
import io.klaude.tool.builtin.TaskUpdateTool;
import io.klaude.tool.builtin.WriteFileTool;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

public final class BuiltinTools {
    // 禁止实例化工具组合工厂
    private BuiltinTools() {}

    // 将全部内置工具与真实 session 服务组合到 registry
    public static ToolRegistry create(
            Path workspace,
            TaskManager tasks,
            SessionStore sessions) throws IOException {
        return create(workspace, tasks, sessions, List.of(), Set.of());
    }

    // 将内置与扩展工具按同一可选白名单组合到 registry
    public static ToolRegistry create(
            Path workspace,
            TaskManager tasks,
            SessionStore sessions,
            List<? extends Tool> extensions,
            Set<String> allowedTools) throws IOException {
        Files.createDirectories(workspace);
        var registry = new ToolRegistry();
        List<Tool> tools = new java.util.ArrayList<>();
        tools.add(new ReadFileTool(workspace));
        tools.add(new ListDirectoryTool(workspace));
        tools.add(new WriteFileTool(workspace));
        tools.add(new BashTool(workspace));
        tools.add(new TaskCreateTool((subject, description, blockedBy) ->
                taskJson(tasks.create(subject, description, blockedBy))));
        tools.add(new TaskGetTool(taskId -> taskJson(tasks.get(taskId))));
        tools.add(new TaskListTool(tasks::formatList));
        tools.add(new TaskUpdateTool(request -> taskJson(tasks.update(
                request.taskId(),
                request.status() == null ? null : TaskStatus.fromWireValue(request.status()),
                request.addBlockedBy(),
                request.removeBlockedBy()))));
        tools.add(new NoteSaveTool(sessions::appendNote));
        tools.addAll(List.copyOf(extensions));
        Set<String> allowed = Set.copyOf(allowedTools);
        tools.stream()
                .filter(tool -> allowed.isEmpty() || allowed.contains(tool.name()))
                .forEach(registry::register);
        return registry;
    }

    // 将 session task 转为工具边界使用的 JSON object
    private static ObjectNode taskJson(Task task) {
        return ProtocolJson.mapper().valueToTree(task);
    }
}
