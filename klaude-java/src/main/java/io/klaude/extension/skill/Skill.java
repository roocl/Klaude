package io.klaude.extension.skill;

import java.util.List;
import java.util.Objects;

public record Skill(
        String name,
        String description,
        String systemPromptTemplate,
        List<String> allowedTools) {
    // 校验 skill 字段并创建 allowed tools 不可变副本
    public Skill {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(systemPromptTemplate, "systemPromptTemplate");
        allowedTools = List.copyOf(Objects.requireNonNull(allowedTools, "allowedTools"));
    }
}
