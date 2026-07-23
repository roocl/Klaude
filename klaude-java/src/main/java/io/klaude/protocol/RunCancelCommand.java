package io.klaude.protocol;

import java.util.Objects;

public record RunCancelCommand(String runId) implements Command {
    // 校验待取消的 run 标识
    public RunCancelCommand {
        Objects.requireNonNull(runId, "runId");
    }
}
