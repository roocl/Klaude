package io.klaude.tool;

public record PermissionOutcome(boolean allowed, String decision) {
    // 校验 permission outcome 的 decision
    public PermissionOutcome {
        java.util.Objects.requireNonNull(decision, "decision");
    }

    // 创建允许结果
    public static PermissionOutcome allow(String decision) {
        return new PermissionOutcome(true, decision);
    }

    // 创建拒绝结果
    public static PermissionOutcome deny(String decision) {
        return new PermissionOutcome(false, decision);
    }
}
