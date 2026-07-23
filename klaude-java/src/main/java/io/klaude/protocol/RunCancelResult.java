package io.klaude.protocol;

public record RunCancelResult(Boolean cancelled) {
    // 校验取消结果字段
    public RunCancelResult {
        cancelled = cancelled == null ? Boolean.FALSE : cancelled;
    }
}
