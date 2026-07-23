package io.klaude.protocol;

import java.util.List;
import java.util.Objects;

public record SkillInfo(String name, String description, List<String> allowedTools) {
    // 校验 skill 列表项并复制工具白名单
    public SkillInfo {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(description, "description");
        allowedTools = List.copyOf(Objects.requireNonNull(allowedTools, "allowedTools"));
    }
}
