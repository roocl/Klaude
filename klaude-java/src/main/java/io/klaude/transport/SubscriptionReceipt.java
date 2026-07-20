package io.klaude.transport;

public record SubscriptionReceipt(String subscriptionId, int replayedCount) {
    // 校验 replay subscription 返回值
    public SubscriptionReceipt {
        java.util.Objects.requireNonNull(subscriptionId, "subscriptionId");
        if (replayedCount < 0) {
            throw new IllegalArgumentException("replayedCount must be non-negative");
        }
    }
}
