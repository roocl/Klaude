package io.klaude.llm;

public final class AnthropicTransportException extends RuntimeException {
    // 保存可 retry 的 Anthropic transport 错误上下文
    public AnthropicTransportException(String message) {
        super(message);
    }

    // 保存可 retry transport 错误的原始 cause
    public AnthropicTransportException(String message, Throwable cause) {
        super(message, cause);
    }
}
