package io.klaude.llm;

public record LlmUsage(
        int inputTokens,
        int outputTokens,
        int cacheReadInputTokens,
        int cacheCreationInputTokens,
        double contextPercentage) {
    // 拒绝负 token 计数和非有限上下文占比
    public LlmUsage {
        if (inputTokens < 0
                || outputTokens < 0
                || cacheReadInputTokens < 0
                || cacheCreationInputTokens < 0) {
            throw new IllegalArgumentException("token counts must be non-negative");
        }
        if (!Double.isFinite(contextPercentage) || contextPercentage < 0) {
            throw new IllegalArgumentException("context percentage must be finite and non-negative");
        }
    }
}
