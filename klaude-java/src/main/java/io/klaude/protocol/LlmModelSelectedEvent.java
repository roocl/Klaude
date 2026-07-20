package io.klaude.protocol;

public record LlmModelSelectedEvent(
        String runId,
        String model,
        String strategy,
        String ts) implements Event {

    // 校验 LLM 模型选择事件字段
    public LlmModelSelectedEvent {
        ProtocolChecks.required(runId, "runId");
        ProtocolChecks.required(model, "model");
        ProtocolChecks.required(strategy, "strategy");
        ProtocolChecks.required(ts, "ts");
    }
}
