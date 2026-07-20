package io.klaude.protocol;

import java.util.Objects;

public record EventSubscribeResult(String subscriptionId, Integer replayedCount) {
    // 校验订阅结果并填充回放计数默认值
    public EventSubscribeResult {
        Objects.requireNonNull(subscriptionId, "subscriptionId");
        replayedCount = replayedCount == null ? 0 : replayedCount;
    }
}
