package io.klaude.protocol;

import java.util.Objects;

public record PongResult(String serverVersion, Long uptimeMs, String receivedAt) {
    // 校验 ping 结果字段
    public PongResult {
        Objects.requireNonNull(serverVersion, "serverVersion");
        Objects.requireNonNull(uptimeMs, "uptimeMs");
        Objects.requireNonNull(receivedAt, "receivedAt");
    }
}
