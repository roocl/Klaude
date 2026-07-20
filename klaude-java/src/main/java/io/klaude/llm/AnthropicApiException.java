package io.klaude.llm;

public final class AnthropicApiException extends RuntimeException {
    private final int statusCode;

    // 保存不可 retry 的 Anthropic HTTP 错误
    public AnthropicApiException(int statusCode, String body) {
        super("Anthropic API returned " + statusCode + ": " + body);
        this.statusCode = statusCode;
    }

    // 返回 HTTP status code
    public int statusCode() {
        return statusCode;
    }
}
