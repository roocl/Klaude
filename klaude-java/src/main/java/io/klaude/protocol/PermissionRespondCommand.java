package io.klaude.protocol;

import java.util.Objects;

public record PermissionRespondCommand(String toolUseId, String decision) implements Command {
    // 校验权限响应命令字段
    public PermissionRespondCommand {
        Objects.requireNonNull(toolUseId, "toolUseId");
        Objects.requireNonNull(decision, "decision");
    }
}
