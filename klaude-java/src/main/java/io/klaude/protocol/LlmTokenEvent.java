package io.klaude.protocol;

public record LlmTokenEvent(String runId, String token, String ts) implements Event {
    // 校验 LLM token 事件字段
    public LlmTokenEvent {
        ProtocolChecks.required(runId, "runId");
        ProtocolChecks.required(token, "token");
        ProtocolChecks.required(ts, "ts");
    }
}
