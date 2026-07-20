package io.klaude.llm;

public record AnthropicStreamRequest(String model, LlmRequest request, int maxTokens) {
    // 校验流式 client 请求字段
    public AnthropicStreamRequest {
        java.util.Objects.requireNonNull(model, "model");
        java.util.Objects.requireNonNull(request, "request");
        if (maxTokens < 1) {
            throw new IllegalArgumentException("maxTokens must be positive");
        }
    }
}
