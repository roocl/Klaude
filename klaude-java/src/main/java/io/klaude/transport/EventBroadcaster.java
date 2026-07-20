package io.klaude.transport;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.klaude.protocol.ProtocolJson;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;
import java.util.function.Consumer;
import java.time.Clock;

public final class EventBroadcaster {
    private final Supplier<String> idSupplier;
    private final Clock clock;
    private final Consumer<ObjectNode> traceSink;
    private final CopyOnWriteArrayList<Subscription> subscriptions = new CopyOnWriteArrayList<>();

    // 初始化 subscription ID 来源
    public EventBroadcaster(Supplier<String> idSupplier) {
        this(idSupplier, Clock.systemUTC(), ignored -> { });
    }

    // 初始化 subscription ID、时间和 trace sink 来源
    public EventBroadcaster(
            Supplier<String> idSupplier, Clock clock, Consumer<ObjectNode> traceSink) {
        this.idSupplier = idSupplier;
        this.clock = clock;
        this.traceSink = traceSink;
    }

    // 注册一个连接的 topic/scope 订阅并返回 subscription ID
    public synchronized String subscribe(
            NdjsonConnection connection, List<String> topics, String scope) {
        String subscriptionId = nextSubscriptionId();
        addSubscription(subscriptionId, connection, topics, scope);
        return subscriptionId;
    }

    // 在一个发布互斥区内 enqueue replay 并注册 live subscription
    public synchronized SubscriptionReceipt subscribeWithReplay(
            NdjsonConnection connection,
            List<String> topics,
            String scope,
            List<ObjectNode> replayEvents) {
        int replayedCount = 0;
        for (ObjectNode event : replayEvents) {
            String eventType = event.path("type").asText();
            String runId = event.hasNonNull("run_id") ? event.path("run_id").asText() : null;
            if (SubscriptionMatcher.matchesTopic(eventType, topics)
                    && SubscriptionMatcher.matchesScope(runId, scope)) {
                connection.send(envelope(event));
                replayedCount++;
            }
        }
        String subscriptionId = nextSubscriptionId();
        addSubscription(subscriptionId, connection, topics, scope);
        return new SubscriptionReceipt(subscriptionId, replayedCount);
    }

    // 移除一个连接的全部订阅
    public void unsubscribe(NdjsonConnection connection) {
        subscriptions.removeIf(subscription -> subscription.connection() == connection);
    }

    // 将事件推送到全部匹配订阅并返回成功写回数量
    public synchronized CompletionStage<Integer> publish(ObjectNode event) {
        String eventType = event.path("type").asText();
        String runId = event.hasNonNull("run_id") ? event.path("run_id").asText() : null;
        ObjectNode envelope = envelope(event);
        List<CompletableFuture<Boolean>> sends = new ArrayList<>();
        for (Subscription subscription : subscriptions) {
            if (!SubscriptionMatcher.matchesTopic(eventType, subscription.topics())
                    || !SubscriptionMatcher.matchesScope(runId, subscription.scope())) {
                continue;
            }
            CompletableFuture<Boolean> send = subscription.connection()
                    .send(envelope)
                    .handle((ignored, error) -> {
                        if (error != null) {
                            unsubscribe(subscription.connection());
                            return false;
                        }
                        tracePush(
                                subscription.connection(), subscription.id(), eventType, runId);
                        return true;
                    })
                    .toCompletableFuture();
            sends.add(send);
        }
        return CompletableFuture.allOf(sends.toArray(CompletableFuture[]::new))
                .thenApply(ignored -> (int) sends.stream().filter(CompletableFuture::join).count());
    }

    // 向一个连接发送 snapshot event 并记录标准 push trace
    public CompletionStage<Void> sendDirect(
            NdjsonConnection connection, String subscriptionId, ObjectNode event) {
        String eventType = event.path("type").asText();
        String runId = event.hasNonNull("run_id") ? event.path("run_id").asText() : null;
        return connection.send(envelope(event)).thenRun(() ->
                tracePush(connection, subscriptionId, eventType, runId));
    }

    // 记录一次成功 event push
    private void tracePush(
            NdjsonConnection connection,
            String subscriptionId,
            String eventType,
            String runId) {
        ObjectNode data = ProtocolJson.mapper().createObjectNode();
        data.put("sub_id", subscriptionId);
        data.put("event_type", eventType);
        TransportTrace.emit(traceSink, TransportTrace.record(
                clock,
                "CORE→CLIENT",
                "push",
                connection,
                runId,
                data));
    }

    // 生成下一个 subscription ID
    private String nextSubscriptionId() {
        String suffix = idSupplier.get();
        if (suffix == null || suffix.isEmpty()) {
            throw new IllegalArgumentException("subscription ID supplier returned no value");
        }
        return "sub-" + suffix.substring(0, Math.min(8, suffix.length()));
    }

    // 添加订阅并绑定 connection close 清理
    private void addSubscription(
            String subscriptionId,
            NdjsonConnection connection,
            List<String> topics,
            String scope) {
        subscriptions.add(new Subscription(
                subscriptionId, connection, List.copyOf(topics), scope));
        connection.onClose(() -> unsubscribe(connection));
    }

    // 构造标准 event push envelope
    private static ObjectNode envelope(ObjectNode event) {
        ObjectNode envelope = ProtocolJson.mapper().createObjectNode();
        envelope.put("kind", "event");
        envelope.set("event", event);
        return envelope;
    }

    // 返回当前订阅数量供生命周期监控和测试观察
    public int subscriptionCount() {
        return subscriptions.size();
    }

    private record Subscription(
            String id,
            NdjsonConnection connection,
            List<String> topics,
            String scope) {
        // 防御性复制 topic 并校验 subscription 基础字段
        private Subscription {
            java.util.Objects.requireNonNull(id, "id");
            java.util.Objects.requireNonNull(connection, "connection");
            topics = List.copyOf(topics);
            java.util.Objects.requireNonNull(scope, "scope");
        }
    }
}
