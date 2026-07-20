package io.klaude.tool.permission;

import java.util.Arrays;
import java.util.Optional;

public enum PolicyDecision {
    ALLOW("allow"),
    DENY("deny");

    private final String wireValue;

    // 保存 policy TOML 中的持久化值
    PolicyDecision(String wireValue) {
        this.wireValue = wireValue;
    }

    // 尝试把持久化文本解析为有效 allow/deny 决策
    public static Optional<PolicyDecision> fromWireValue(String value) {
        return Arrays.stream(values())
                .filter(decision -> decision.wireValue.equals(value))
                .findFirst();
    }

    // 返回 policy TOML 使用的持久化值
    public String wireValue() {
        return wireValue;
    }
}
