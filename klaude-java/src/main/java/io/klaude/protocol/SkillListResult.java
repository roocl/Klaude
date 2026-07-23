package io.klaude.protocol;

import java.util.List;
import java.util.Objects;

public record SkillListResult(List<SkillInfo> skills) {
    // 创建不可变 skill 列表
    public SkillListResult {
        skills = List.copyOf(Objects.requireNonNull(skills, "skills"));
    }
}
