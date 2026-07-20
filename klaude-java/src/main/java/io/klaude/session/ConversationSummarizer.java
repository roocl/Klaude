package io.klaude.session;

import java.util.concurrent.CompletionStage;

@FunctionalInterface
public interface ConversationSummarizer {
    // 根据完整会话历史与可选 focus 异步生成摘要
    CompletionStage<ConversationSummary> summarize(ConversationSummaryRequest request);
}
