package io.klaude.tool;

public enum ToolErrorType {
    RUNTIME_ERROR("runtime_error"),
    TIMEOUT("timeout"),
    SCHEMA_ERROR("schema_error"),
    PERMISSION_DENIED("permission_denied"),
    RATE_LIMITED("rate_limited");

    private final String wireValue;

    // 保存工具错误分类的 wire value
    ToolErrorType(String wireValue) {
        this.wireValue = wireValue;
    }

    // 返回工具错误分类的 wire value
    public String wireValue() {
        return wireValue;
    }
}
