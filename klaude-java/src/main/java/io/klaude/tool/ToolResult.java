package io.klaude.tool;

public record ToolResult(String content, boolean isError, ToolErrorType errorType) {
    // 校验工具结果的内容与错误分类一致性
    public ToolResult {
        java.util.Objects.requireNonNull(content, "content");
        if (isError && errorType == null) {
            throw new IllegalArgumentException("error result requires errorType");
        }
        if (!isError && errorType != null) {
            throw new IllegalArgumentException("success result cannot have errorType");
        }
    }

    // 创建一个成功工具结果
    public static ToolResult success(String content) {
        return new ToolResult(content, false, null);
    }

    // 创建一个失败工具结果
    public static ToolResult failure(String content, ToolErrorType errorType) {
        return new ToolResult(content, true, errorType);
    }
}
