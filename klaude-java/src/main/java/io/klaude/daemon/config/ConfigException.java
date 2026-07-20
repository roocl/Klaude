package io.klaude.daemon.config;

public final class ConfigException extends RuntimeException {
    // 创建带上下文的配置异常
    public ConfigException(String message) {
        super(message);
    }

    // 创建保留原始原因的配置异常
    public ConfigException(String message, Throwable cause) {
        super(message, cause);
    }
}
