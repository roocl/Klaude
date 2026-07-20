package io.klaude.session;

import java.util.List;
import java.util.Objects;

public record SessionPrompt(
        String goal,
        String systemPromptOverride,
        List<String> allowedTools,
        String skillName,
        String arguments) {
    // 校验解析后的 session prompt 并创建工具白名单副本
    public SessionPrompt {
        Objects.requireNonNull(goal, "goal");
        allowedTools = List.copyOf(Objects.requireNonNull(allowedTools, "allowedTools"));
        Objects.requireNonNull(skillName, "skillName");
        Objects.requireNonNull(arguments, "arguments");
    }

    // 创建不含 skill 覆盖的原样 prompt
    public static SessionPrompt unchanged(String content) {
        return new SessionPrompt(content, null, List.of(), "", "");
    }
}
