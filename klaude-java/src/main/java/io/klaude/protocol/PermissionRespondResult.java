package io.klaude.protocol;

public record PermissionRespondResult(Boolean ok) {
    // 填充权限响应结果默认值
    public PermissionRespondResult {
        ok = ok == null ? Boolean.TRUE : ok;
    }
}
