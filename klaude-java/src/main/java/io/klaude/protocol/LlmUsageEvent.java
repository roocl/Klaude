package io.klaude.protocol;

public record LlmUsageEvent(
        String runId,
        Integer inputTokens,
        Integer outputTokens,
        Integer cacheReadInputTokens,
        Integer cacheCreationInputTokens,
        Double contextPct,
        String ts) implements Event {

    // 校验 LLM 用量事件并填充上下文占比
    public LlmUsageEvent {
        ProtocolChecks.required(runId, "runId");
        ProtocolChecks.required(inputTokens, "inputTokens");
        ProtocolChecks.required(outputTokens, "outputTokens");
        ProtocolChecks.required(cacheReadInputTokens, "cacheReadInputTokens");
        ProtocolChecks.required(cacheCreationInputTokens, "cacheCreationInputTokens");
        contextPct = contextPct == null ? 0.0 : contextPct;
        ProtocolChecks.required(ts, "ts");
    }
}
