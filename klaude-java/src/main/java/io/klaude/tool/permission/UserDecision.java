package io.klaude.tool.permission;

import java.util.Arrays;

public enum UserDecision {
    ALLOW_ONCE("allow_once", true, null),
    DENY_ONCE("deny_once", false, null),
    ALWAYS_ALLOW("always_allow", true, PolicyDecision.ALLOW),
    ALWAYS_DENY("always_deny", false, PolicyDecision.DENY);

    private final String wireValue;
    private final boolean allowed;
    private final PolicyDecision persistentDecision;

    // 保存用户 decision 的 wire、allow 和持久化含义
    UserDecision(String wireValue, boolean allowed, PolicyDecision persistentDecision) {
        this.wireValue = wireValue;
        this.allowed = allowed;
        this.persistentDecision = persistentDecision;
    }

    // 按 wire value 解析用户 decision
    public static UserDecision fromWireValue(String value) {
        return Arrays.stream(values())
                .filter(decision -> decision.wireValue.equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown permission decision: " + value));
    }

    // 返回用户 decision 的 wire value
    public String wireValue() {
        return wireValue;
    }

    // 返回该 decision 是否允许执行
    public boolean allowed() {
        return allowed;
    }

    // 返回需要持久化的 allow/deny 或 null
    public PolicyDecision persistentDecision() {
        return persistentDecision;
    }
}
