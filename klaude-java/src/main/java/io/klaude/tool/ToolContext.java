package io.klaude.tool;

import java.nio.file.Path;

public record ToolContext(
        Path workingDirectory,
        String sessionId,
        String runId,
        String toolUseId) {
    // 规范化工作目录并校验调用上下文字段
    public ToolContext {
        workingDirectory = java.util.Objects.requireNonNull(
                workingDirectory, "workingDirectory").toAbsolutePath().normalize();
        java.util.Objects.requireNonNull(sessionId, "sessionId");
        java.util.Objects.requireNonNull(runId, "runId");
        java.util.Objects.requireNonNull(toolUseId, "toolUseId");
    }
}
