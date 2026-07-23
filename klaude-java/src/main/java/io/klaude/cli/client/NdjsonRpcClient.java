package io.klaude.cli.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.klaude.protocol.Command;
import io.klaude.protocol.Event;
import io.klaude.protocol.JsonRpcRequest;
import io.klaude.protocol.ProtocolJson;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

public final class NdjsonRpcClient implements AutoCloseable {
    private final Socket socket;
    private final BufferedReader reader;
    private final BufferedWriter writer;
    private final Consumer<Event> events;
    private final AtomicLong requestIds = new AtomicLong();
    private final ConcurrentHashMap<String, CompletableFuture<JsonNode>> pending =
            new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final CompletableFuture<Void> termination = new CompletableFuture<>();
    private final Thread readerThread;

    // 连接 daemon 并启动混合消息读取循环
    public NdjsonRpcClient(
            String host,
            int port,
            Duration connectTimeout,
            Consumer<Event> events) throws IOException {
        this.events = java.util.Objects.requireNonNull(events, "events");
        socket = new Socket();
        socket.connect(
                new InetSocketAddress(host, port),
                Math.toIntExact(connectTimeout.toMillis()));
        reader = new BufferedReader(new InputStreamReader(
                socket.getInputStream(), StandardCharsets.UTF_8));
        writer = new BufferedWriter(new OutputStreamWriter(
                socket.getOutputStream(), StandardCharsets.UTF_8));
        readerThread = Thread.ofVirtual().name("klaude-cli-rpc-reader").start(this::readLoop);
    }

    // 发送一个协议命令并将结果解析为指定 record
    public <T> CompletableFuture<T> request(
            String method, Command command, Class<T> resultType) {
        if (closed.get()) {
            return CompletableFuture.failedFuture(new IOException("daemon connection is closed"));
        }
        String id = Long.toString(requestIds.incrementAndGet());
        CompletableFuture<JsonNode> response = new CompletableFuture<>();
        pending.put(id, response);
        try {
            ObjectNode params = ProtocolJson.mapper().valueToTree(command);
            write(new JsonRpcRequest("2.0", id, method, params));
        } catch (Throwable error) {
            pending.remove(id);
            response.completeExceptionally(error);
        }
        return response.thenApply(result -> convert(result, resultType));
    }

    // 返回连接终止信号供交互客户端更新状态
    public CompletableFuture<Void> termination() {
        return termination;
    }

    // 将响应树严格转换为目标结果类型
    private static <T> T convert(JsonNode result, Class<T> resultType) {
        try {
            return ProtocolJson.mapper().treeToValue(result, resultType);
        } catch (IOException error) {
            throw new IllegalStateException("invalid daemon response", error);
        }
    }

    // 在写锁内发送一条 UTF-8 NDJSON 请求
    private synchronized void write(JsonRpcRequest request) throws IOException {
        writer.write(ProtocolJson.mapper().writeValueAsString(request));
        writer.newLine();
        writer.flush();
    }

    // 持续读取响应或事件直到连接关闭
    private void readLoop() {
        Throwable failure = null;
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                dispatch(ProtocolJson.mapper().readTree(line));
            }
            failure = new EOFException("daemon closed the connection");
        } catch (Throwable error) {
            failure = error;
        } finally {
            Throwable cause = failure == null ? new IOException("daemon connection closed") : failure;
            failPending(cause);
            if (closed.get()) {
                termination.complete(null);
            } else {
                termination.completeExceptionally(cause);
            }
            closeSocket();
        }
    }

    // 按 envelope 类型分派一个完整 JSON 消息
    private void dispatch(JsonNode message) throws IOException {
        if (message.path("kind").asText().equals("event")) {
            events.accept(ProtocolJson.mapper().treeToValue(message.path("event"), Event.class));
            return;
        }
        JsonNode idNode = message.get("id");
        if (idNode == null || idNode.isNull()) {
            throw new IOException("response missing request id");
        }
        CompletableFuture<JsonNode> response = pending.remove(idNode.asText());
        if (response == null) {
            return;
        }
        JsonNode error = message.get("error");
        if (error != null && !error.isNull()) {
            response.completeExceptionally(new RpcException(
                    error.path("code").asInt(),
                    error.path("message").asText("RPC request failed"),
                    error.get("data")));
        } else {
            response.complete(message.get("result"));
        }
    }

    // 让全部等待中的请求以同一连接错误结束
    private void failPending(Throwable error) {
        pending.forEach((id, future) -> future.completeExceptionally(error));
        pending.clear();
    }

    // 关闭底层 socket 并忽略重复关闭错误
    private void closeSocket() {
        try {
            socket.close();
        } catch (IOException ignored) {
            // Closing an already failed socket is harmless.
        }
    }

    // 关闭连接并终止全部未完成请求
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        closeSocket();
        failPending(new IOException("daemon connection closed"));
        if (Thread.currentThread() != readerThread) {
            try {
                readerThread.join(Duration.ofSeconds(2));
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
