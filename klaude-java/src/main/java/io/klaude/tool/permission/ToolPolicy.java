package io.klaude.tool.permission;

import java.util.List;

public record ToolPolicy(
        PermissionAction defaultAction,
        List<String> allowPatterns,
        List<String> denyPatterns) {
    // 防御性复制 policy patterns 并校验默认动作
    public ToolPolicy {
        java.util.Objects.requireNonNull(defaultAction, "defaultAction");
        allowPatterns = List.copyOf(allowPatterns);
        denyPatterns = List.copyOf(denyPatterns);
    }

    // 创建默认 ASK policy
    public static ToolPolicy ask() {
        return new ToolPolicy(PermissionAction.ASK, List.of(), List.of());
    }

    // 创建默认 ALLOW policy
    public static ToolPolicy allow() {
        return new ToolPolicy(PermissionAction.ALLOW, List.of(), List.of());
    }

    // 创建默认 DENY policy
    public static ToolPolicy deny() {
        return new ToolPolicy(PermissionAction.DENY, List.of(), List.of());
    }
}
