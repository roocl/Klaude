package io.klaude.protocol;

import java.util.Objects;

public record AgentRunCommand(String goal) implements Command {
    // 校验 Agent 运行目标
    public AgentRunCommand {
        Objects.requireNonNull(goal, "goal");
    }
}
