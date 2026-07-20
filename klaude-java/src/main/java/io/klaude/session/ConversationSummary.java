package io.klaude.session;

import java.util.Objects;

public record ConversationSummary(String text, int summaryTokens) {
    // 校验摘要文本与非负 token 数
    public ConversationSummary {
        Objects.requireNonNull(text, "text");
        if (summaryTokens < 0) {
            throw new IllegalArgumentException("summaryTokens must not be negative");
        }
    }
}
