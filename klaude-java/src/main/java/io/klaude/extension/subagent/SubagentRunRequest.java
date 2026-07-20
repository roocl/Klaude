package io.klaude.extension.subagent;

import io.klaude.extension.profile.AgentProfile;
import java.util.Objects;

public record SubagentRunRequest(
        String runId,
        String parentRunId,
        String sessionId,
        String prompt,
        AgentProfile profile,
        int depth) {
    // 校验 child run 身份、prompt 与深度
    public SubagentRunRequest {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(parentRunId, "parentRunId");
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(prompt, "prompt");
        if (depth < 1) {
            throw new IllegalArgumentException("child depth must be positive");
        }
    }
}
