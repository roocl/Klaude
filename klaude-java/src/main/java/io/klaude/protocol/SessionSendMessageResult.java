package io.klaude.protocol;

import java.util.Objects;

public record SessionSendMessageResult(String runId) {
    // 校验发送消息结果的 run 标识
    public SessionSendMessageResult {
        Objects.requireNonNull(runId, "runId");
    }
}
