package io.klaude.protocol;

import java.util.Objects;

public record AgentRunResult(String runId) {
    // 校验 Agent run 结果标识
    public AgentRunResult {
        Objects.requireNonNull(runId, "runId");
    }
}
