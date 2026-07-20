package io.klaude.transport;

public final class InvalidParamsException extends RuntimeException {
    // 保存可返回给客户端的参数校验错误消息
    public InvalidParamsException(String message) {
        super(message);
    }

    // 保存带底层原因的参数校验错误消息
    public InvalidParamsException(String message, Throwable cause) {
        super(message, cause);
    }
}
