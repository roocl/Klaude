package io.klaude.cli;

import io.klaude.cli.client.DaemonClient;
import io.klaude.cli.client.NdjsonRpcClient;
import java.io.BufferedReader;
import java.io.PrintStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletionException;

public final class CliMain {
    // 禁止实例化 CLI 主入口类
    private CliMain() {
    }

    // 解析命令、连接 daemon 并返回进程退出码
    public static void main(String[] args) {
        int exitCode = execute(args, System.out, System.err);
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    // 执行一个 CLI 命令并隔离用户可见错误
    static int execute(String[] args, PrintStream output, PrintStream errors) {
        final CliArguments arguments;
        try {
            arguments = CliArguments.parse(args);
        } catch (IllegalArgumentException error) {
            errors.println("error: " + error.getMessage());
            printUsage(errors);
            return 2;
        }
        if (arguments.command().equals("help")) {
            printUsage(output);
            return 0;
        }
        Path cwd = Path.of("").toAbsolutePath().normalize();
        Path home = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize();
        final CliEndpoint endpoint;
        try {
            endpoint = CliEndpoint.load(cwd, home, System.getenv());
        } catch (RuntimeException error) {
            errors.println("error: invalid CLI configuration (" + rootMessage(error) + ")");
            return 2;
        }
        if (arguments.command().equals("trace")) {
            return TracePrinter.print(endpoint.traceFile(), output, errors);
        }
        var input = TerminalInput.open();
        var runs = new RunWaiter();
        try (var terminal = new TerminalEvents(input, output, errors, runs);
             var rpc = new NdjsonRpcClient(
                     endpoint.host(), endpoint.port(), Duration.ofSeconds(3), terminal)) {
            var daemon = new DaemonClient(rpc);
            terminal.bind(daemon);
            if (!arguments.command().equals("ping")) {
                daemon.ping().join();
            }
            return switch (arguments.command()) {
                case "ping" -> ping(daemon, output);
                case "run" -> run(daemon, runs, arguments.goal());
                case "chat" -> chat(daemon, input, output);
                case "cancel" -> cancel(daemon, arguments.goal(), output);
                default -> throw new IllegalStateException("unreachable command");
            };
        } catch (CompletionException error) {
            if (isConnectionFailure(error)) {
                errors.println("error: daemon connection lost; ensure klaude-core-java is running");
                return 3;
            }
            errors.println("error: " + rootMessage(error));
            return 1;
        } catch (Exception error) {
            errors.println("error: cannot connect to daemon at "
                    + endpoint.host() + ":" + endpoint.port() + " (" + rootMessage(error) + ")");
            return isConnectionFailure(error) ? 3 : 1;
        }
    }

    // 请求取消 run 并以退出码区分未找到目标
    private static int cancel(DaemonClient daemon, String runId, PrintStream output) {
        boolean cancelled = daemon.cancelRun(runId).join().cancelled();
        output.println(cancelled ? "cancelled " + runId : "run not active: " + runId);
        return cancelled ? 0 : 1;
    }

    // 执行 ping 并打印 daemon 版本与 uptime
    private static int ping(DaemonClient daemon, PrintStream output) {
        var pong = daemon.ping().join();
        output.println("pong server=" + pong.serverVersion() + " uptime=" + pong.uptimeMs() + "ms");
        return 0;
    }

    // 订阅事件、启动独立 goal 并等待其结束
    private static int run(DaemonClient daemon, RunWaiter runs, String goal) {
        daemon.subscribeAll().join();
        var started = daemon.run(goal).join();
        Thread cancellation = Thread.ofPlatform().unstarted(() -> {
            try {
                daemon.cancelRun(started.runId()).get(2, java.util.concurrent.TimeUnit.SECONDS);
            } catch (Exception ignored) {
                // Process shutdown remains authoritative when daemon is unavailable.
            }
        });
        Runtime.getRuntime().addShutdownHook(cancellation);
        try {
            var finished = runs.await(started.runId()).join();
            return finished.status().equals("success") ? 0 : 1;
        } finally {
            try {
                Runtime.getRuntime().removeShutdownHook(cancellation);
            } catch (IllegalStateException ignored) {
                // JVM shutdown already started and will execute the cancellation hook.
            }
        }
    }

    // 创建 session 并循环发送普通文本或 slash skill
    private static int chat(
            DaemonClient daemon, BufferedReader input, PrintStream output) throws Exception {
        daemon.subscribeAll().join();
        var session = daemon.createChatSession("").join();
        output.println("Klaude chat");
        String sessionId = session.sessionId();
        output.println("Session: " + sessionId);
        while (true) {
            output.print("You> ");
            output.flush();
            String line = input.readLine();
            if (line == null || line.strip().equalsIgnoreCase("/exit")) {
                return 0;
            }
            if (line.isBlank()) {
                continue;
            }
            String command = firstWord(line);
            if (command.equals("/sessions")) {
                printSessions(daemon, output);
            } else if (command.equals("/skills")) {
                printSkills(daemon, output);
            } else if (command.equals("/history")) {
                printHistory(daemon, sessionId, output);
            } else if (command.equals("/compact")) {
                compact(daemon, sessionId, remainder(line), output);
            } else if (command.equals("/resume")) {
                String requested = remainder(line);
                if (requested.isBlank()) {
                    output.println("usage: /resume <session-id>");
                } else {
                    daemon.getHistory(requested).join();
                    sessionId = requested;
                    output.println("Session: " + sessionId);
                }
            } else {
                daemon.sendMessage(sessionId, line).join();
            }
        }
    }

    // 打印按最近更新时间排序的 session 列表
    private static void printSessions(DaemonClient daemon, PrintStream output) {
        var sessions = daemon.listSessions().join().sessions();
        if (sessions.isEmpty()) {
            output.println("No sessions.");
            return;
        }
        for (var session : sessions) {
            String title = session.title().isBlank() ? "(untitled)" : session.title();
            output.println(session.sessionId() + "  " + session.status().wireValue()
                    + "  " + title + "  " + session.updatedAt());
        }
    }

    // 打印 daemon 可解析的 skill catalog
    private static void printSkills(DaemonClient daemon, PrintStream output) {
        var skills = daemon.listSkills().join().skills();
        if (skills.isEmpty()) {
            output.println("No skills.");
            return;
        }
        for (var skill : skills) {
            output.println("/" + skill.name() + "  " + skill.description());
        }
    }

    // 以可读文本打印当前 session 历史
    private static void printHistory(
            DaemonClient daemon, String sessionId, PrintStream output) {
        var messages = daemon.getHistory(sessionId).join().messages();
        if (messages.isEmpty()) {
            output.println("No history.");
            return;
        }
        for (var message : messages) {
            var content = message.path("content");
            output.println(message.path("role").asText("unknown") + "> "
                    + (content.isTextual() ? content.asText() : content.toString()));
        }
    }

    // 请求持久化压缩并打印 token 结果
    private static void compact(
            DaemonClient daemon, String sessionId, String focus, PrintStream output) {
        var result = daemon.compact(sessionId, focus).join();
        output.println("Compacted: summary=" + result.summaryTokens()
                + " saved=" + result.savedTokens() + " tokens");
    }

    // 返回输入中首个空白分隔 token
    private static String firstWord(String input) {
        int space = input.indexOf(' ');
        return space < 0 ? input : input.substring(0, space);
    }

    // 返回首个命令 token 后的参数文本
    private static String remainder(String input) {
        int space = input.indexOf(' ');
        return space < 0 ? "" : input.substring(space + 1).strip();
    }

    // 返回异常链最深处的非空消息
    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    // 判断异常链是否代表 socket 建连或断线失败
    private static boolean isConnectionFailure(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof java.io.IOException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    // 打印第一阶段 CLI 用法
    private static void printUsage(PrintStream output) {
        output.println("Usage:");
        output.println("  klaude ping");
        output.println("  klaude run --goal <text>");
        output.println("  klaude chat");
        output.println("  klaude cancel <run-id>");
        output.println("  klaude trace");
    }
}
