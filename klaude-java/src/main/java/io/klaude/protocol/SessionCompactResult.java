package io.klaude.protocol;

import java.util.Objects;

public record SessionCompactResult(Integer summaryTokens, Integer savedTokens) {
    // 校验会话压缩结果计数
    public SessionCompactResult {
        Objects.requireNonNull(summaryTokens, "summaryTokens");
        Objects.requireNonNull(savedTokens, "savedTokens");
    }
}
