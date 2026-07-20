package io.klaude.protocol;

public record ContextCompactedEvent(
        String sessionId,
        String runId,
        Integer originalTokens,
        Integer summaryTokens,
        String ts) implements Event {

    // 校验上下文压缩事件字段
    public ContextCompactedEvent {
        ProtocolChecks.required(sessionId, "sessionId");
        ProtocolChecks.required(runId, "runId");
        ProtocolChecks.required(originalTokens, "originalTokens");
        ProtocolChecks.required(summaryTokens, "summaryTokens");
        ProtocolChecks.required(ts, "ts");
    }
}
