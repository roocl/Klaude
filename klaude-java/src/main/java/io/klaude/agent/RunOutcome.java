package io.klaude.agent;

public record RunOutcome(
        String runId,
        String status,
        String result,
        String reason,
        int steps) {
    // 校验 run outcome 的必填字段与步数
    public RunOutcome {
        java.util.Objects.requireNonNull(runId, "runId");
        java.util.Objects.requireNonNull(status, "status");
        java.util.Objects.requireNonNull(result, "result");
        if (steps < 0) {
            throw new IllegalArgumentException("steps must be non-negative");
        }
    }
}
