package io.klaude.extension.profile;

import java.util.List;
import java.util.Objects;

public record AgentProfile(
        String name,
        String description,
        String systemPrompt,
        List<String> allowedTools,
        String model) {
    // 校验 profile 字段并创建 allowed tools 不可变副本
    public AgentProfile {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(systemPrompt, "systemPrompt");
        allowedTools = List.copyOf(Objects.requireNonNull(allowedTools, "allowedTools"));
        Objects.requireNonNull(model, "model");
    }
}
