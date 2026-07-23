package io.klaude.cli;

import io.klaude.cli.client.DaemonClient;
import io.klaude.protocol.Event;
import io.klaude.protocol.LlmTokenEvent;
import io.klaude.protocol.LogLineEvent;
import io.klaude.protocol.PermissionRequestedEvent;
import io.klaude.protocol.RunFinishedEvent;
import io.klaude.protocol.SessionWaitingForInputEvent;
import io.klaude.protocol.SkillInvokedEvent;
import io.klaude.protocol.ToolCallFailedEvent;
import io.klaude.protocol.ToolCallFinishedEvent;
import io.klaude.protocol.ToolCallStartedEvent;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

final class TerminalEvents implements Consumer<Event>, AutoCloseable {
    private final BufferedReader input;
    private final PrintStream output;
    private final PrintStream errors;
    private final RunWaiter runs;
    private final AtomicReference<DaemonClient> client = new AtomicReference<>();
    private final ExecutorService permissionPrompts = Executors.newVirtualThreadPerTaskExecutor();
    private boolean inline;

    // 初始化终端事件渲染和权限输入边界
    TerminalEvents(
            BufferedReader input, PrintStream output, PrintStream errors, RunWaiter runs) {
        this.input = input;
        this.output = output;
        this.errors = errors;
        this.runs = runs;
    }

    // 在 RPC 客户端构造完成后绑定高层 daemon client
    void bind(DaemonClient daemonClient) {
        client.set(java.util.Objects.requireNonNull(daemonClient, "daemonClient"));
    }

    // 格式化一个实时事件并转交生命周期信号
    @Override
    public synchronized void accept(Event event) {
        if (event instanceof LlmTokenEvent token) {
            output.print(token.token());
            output.flush();
            inline = true;
            return;
        }
        newline();
        if (event instanceof ToolCallStartedEvent tool) {
            output.println("[tool] " + tool.toolName() + " " + tool.params());
        } else if (event instanceof ToolCallFinishedEvent tool) {
            output.println("[tool] " + tool.toolName() + " completed " + tool.elapsedMs() + "ms");
        } else if (event instanceof ToolCallFailedEvent tool) {
            errors.println("[tool] " + tool.toolName() + " failed: " + tool.errorMessage());
        } else if (event instanceof LogLineEvent log && log.level().equalsIgnoreCase("ERROR")) {
            errors.println("[" + log.source() + "] " + log.message());
        } else if (event instanceof SkillInvokedEvent skill) {
            output.println("[skill] " + skill.skillName());
        } else if (event instanceof PermissionRequestedEvent permission) {
            permissionPrompts.submit(() -> promptPermission(permission));
        } else if (event instanceof RunFinishedEvent finished) {
            output.println("[run] " + finished.status() + " " + finished.steps() + " steps");
            runs.finished(finished);
        } else if (event instanceof SessionWaitingForInputEvent) {
            output.println("[waiting for input]");
        }
    }

    // 补齐流式 token 之后的终端换行
    private void newline() {
        if (inline) {
            output.println();
            inline = false;
        }
    }

    // 读取一次权限决定并异步回复 daemon
    private void promptPermission(PermissionRequestedEvent event) {
        try {
            synchronized (this) {
                newline();
                output.println("[permission] " + event.toolName() + " " + event.paramPreview());
                output.print("Allow? [y] once, [a] always, [n] deny: ");
                output.flush();
            }
            String answer = input.readLine();
            String decision = switch (answer == null ? "" : answer.strip().toLowerCase()) {
                case "y", "yes" -> "allow_once";
                case "a", "always" -> "always_allow";
                default -> "deny_once";
            };
            DaemonClient daemonClient = client.get();
            if (daemonClient != null) {
                daemonClient.respondPermission(event.toolUseId(), decision).join();
            }
        } catch (IOException | RuntimeException error) {
            errors.println("permission response failed: " + error.getMessage());
        }
    }

    // 停止仍在等待终端输入的权限任务
    @Override
    public void close() {
        permissionPrompts.shutdownNow();
    }
}
