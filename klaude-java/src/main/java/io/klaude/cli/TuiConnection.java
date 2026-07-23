package io.klaude.cli;

import io.klaude.cli.client.DaemonClient;
import io.klaude.cli.client.NdjsonRpcClient;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

final class TuiConnection implements AutoCloseable {
    private static final long[] BACKOFF_MS = {250, 500, 1_000, 2_000, 5_000};
    private final CliEndpoint endpoint;
    private final TuiModel model;
    private final TuiRenderer renderer;
    private final AtomicReference<DaemonClient> client = new AtomicReference<>();
    private final AtomicReference<NdjsonRpcClient> rpc = new AtomicReference<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean reconnecting = new AtomicBoolean();
    private final AtomicReference<String> sessionId = new AtomicReference<>();

    // 保存连接参数与 TUI 状态回调
    TuiConnection(CliEndpoint endpoint, TuiModel model, TuiRenderer renderer) {
        this.endpoint = endpoint;
        this.model = model;
        this.renderer = renderer;
    }

    // 建立初始连接并订阅实时事件
    void connect() throws IOException {
        connectOnce(null, false);
    }

    // 返回当前可用 daemon client
    DaemonClient client() {
        DaemonClient current = client.get();
        if (current == null) {
            throw new IllegalStateException("daemon is disconnected");
        }
        return current;
    }

    // 返回当前客户端或在断线窗口返回空值
    DaemonClient clientOrNull() {
        return client.get();
    }

    // 保存需要在重连后继续使用的 session
    void attachSession(String value) {
        sessionId.set(java.util.Objects.requireNonNull(value, "value"));
    }

    // 创建一条连接并按可选 run 锚点回放
    private void connectOnce(String replayRun, boolean recovered) throws IOException {
        var nextRpc = new NdjsonRpcClient(
                endpoint.host(), endpoint.port(), Duration.ofSeconds(3), event -> {
                    model.accept(event);
                    renderer.render(model.snapshot());
                });
        var nextClient = new DaemonClient(nextRpc);
        try {
            nextClient.ping().join();
            String anchor = resolveReplayAnchor(nextClient, replayRun);
            int replayed = nextClient.subscribeAll(anchor).join().replayedCount();
            rpc.set(nextRpc);
            client.set(nextClient);
            if (recovered) {
                model.reconnected(replayed, anchor != null);
                renderer.render(model.snapshot());
            }
            nextRpc.termination().whenComplete((ignored, error) -> {
                if (error != null && !closed.get() && rpc.compareAndSet(nextRpc, null)) {
                    client.compareAndSet(nextClient, null);
                    disconnected(error);
                }
            });
        } catch (RuntimeException error) {
            nextRpc.close();
            throw error;
        }
    }

    // 标记断线并启动单个后台重连循环
    private void disconnected(Throwable error) {
        model.disconnected(TuiMain.rootMessage(error));
        renderer.render(model.snapshot());
        if (reconnecting.compareAndSet(false, true)) {
            Thread.ofVirtual().name("klaude-tui-reconnect").start(this::reconnectLoop);
        }
    }

    // 以有上限的退避持续恢复连接和事件订阅
    private void reconnectLoop() {
        int attempt = 0;
        try {
            while (!closed.get()) {
                try {
                    Thread.sleep(BACKOFF_MS[Math.min(attempt, BACKOFF_MS.length - 1)]);
                    connectOnce(model.replayRunId(), true);
                    return;
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Exception error) {
                    attempt++;
                }
            }
        } finally {
            reconnecting.set(false);
        }
    }

    // 优先使用已观察 run，否则从 session metadata 恢复最近 run
    private String resolveReplayAnchor(DaemonClient nextClient, String observed) {
        if (observed != null) {
            return observed;
        }
        String attached = sessionId.get();
        if (attached == null) {
            return null;
        }
        return nextClient.listSessions().join().sessions().stream()
                .filter(session -> session.sessionId().equals(attached))
                .map(session -> session.lastRunId().isBlank() ? null : session.lastRunId())
                .findFirst()
                .orElse(null);
    }

    // 关闭当前连接并停止后续重连
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        client.set(null);
        NdjsonRpcClient current = rpc.getAndSet(null);
        if (current != null) {
            current.close();
        }
    }
}
