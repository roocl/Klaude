package io.klaude.transport;

import com.fasterxml.jackson.databind.JsonNode;
import io.klaude.protocol.ProtocolJson;
import io.netty.channel.Channel;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public final class NdjsonConnection {
    private final String id;
    private final Channel channel;

    // 保存连接 ID 和对应 Netty channel
    NdjsonConnection(String id, Channel channel) {
        this.id = id;
        this.channel = channel;
    }

    // 返回服务器生命周期内唯一的连接 ID
    public String id() {
        return id;
    }

    // 返回连接当前是否仍可写
    public boolean isOpen() {
        return channel.isActive();
    }

    // 注册连接关闭后的轻量清理回调
    public void onClose(Runnable callback) {
        channel.closeFuture().addListener(ignored -> callback.run());
    }

    // 将 JSON tree 序列化为一条完整 UTF-8 NDJSON 并异步写回
    public CompletionStage<Void> send(JsonNode message) {
        CompletableFuture<Void> completion = new CompletableFuture<>();
        final String line;
        try {
            line = ProtocolJson.mapper().writeValueAsString(message) + "\n";
        } catch (Exception error) {
            completion.completeExceptionally(error);
            return completion;
        }
        channel.writeAndFlush(line).addListener(future -> {
            if (future.isSuccess()) {
                completion.complete(null);
            } else {
                completion.completeExceptionally(future.cause());
            }
        });
        return completion;
    }

    // 异步关闭当前客户端连接
    public CompletionStage<Void> close() {
        CompletableFuture<Void> completion = new CompletableFuture<>();
        channel.close().addListener(future -> {
            if (future.isSuccess()) {
                completion.complete(null);
            } else {
                completion.completeExceptionally(future.cause());
            }
        });
        return completion;
    }
}
