package io.klaude.session;

public final class SessionException extends RuntimeException {
    private final int code;

    // 创建携带稳定 JSON-RPC code 的 session 域异常
    public SessionException(int code, String message) {
        super(message);
        this.code = code;
    }

    // 返回兼容的 JSON-RPC error code
    public int code() {
        return code;
    }
}
