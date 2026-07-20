package io.klaude.tool;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class ToolRegistry {
    private final Map<String, Tool> tools = new LinkedHashMap<>();

    // 注册工具并让同名新实例覆盖旧实例
    public synchronized void register(Tool tool) {
        Tool value = java.util.Objects.requireNonNull(tool, "tool");
        if (value.name().isBlank()) {
            throw new IllegalArgumentException("tool name must not be blank");
        }
        tools.put(value.name(), value);
    }

    // 按名称返回已注册工具
    public synchronized Optional<Tool> get(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    // 按稳定注册顺序返回不可变 provider definitions
    public synchronized List<ToolDefinition> definitions() {
        return tools.values().stream()
                .map(tool -> new ToolDefinition(
                        tool.name(), tool.description(), tool.inputSchema()))
                .toList();
    }
}
