package io.klaude.cli;

import io.klaude.cli.client.DaemonClient;
import io.klaude.protocol.PermissionRequestedEvent;
import io.klaude.protocol.SessionInfo;
import io.klaude.protocol.SkillInfo;
import java.io.BufferedReader;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletionException;

public final class TuiMain {
    // 禁止实例化 TUI 主入口类
    private TuiMain() {
    }

    // 启动终端界面并映射退出码
    public static void main(String[] args) {
        int exitCode = execute(args);
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    // 连接 daemon、恢复可选会话并运行输入循环
    static int execute(String[] args) {
        var model = new TuiModel();
        var renderer = TuiRenderer.create(System.out, System.console() != null, System.getenv());
        Path cwd = Path.of("").toAbsolutePath().normalize();
        Path home = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize();
        try {
            CliEndpoint endpoint = CliEndpoint.load(cwd, home, System.getenv());
            try (var connection = new TuiConnection(endpoint, model, renderer)) {
                connection.connect();
                var daemon = connection.client();
                String requestedSession = parseSession(args);
                String sessionId;
                if (requestedSession.isEmpty()) {
                    sessionId = daemon.createChatSession("").join().sessionId();
                } else {
                    daemon.getHistory(requestedSession).join();
                    sessionId = requestedSession;
                }
                model.session(sessionId);
                connection.attachSession(sessionId);
                return inputLoop(connection, model, renderer, sessionId);
            }
        } catch (IllegalArgumentException error) {
            System.err.println("error: " + error.getMessage());
            return 2;
        } catch (Exception error) {
            System.err.println("error: " + rootMessage(error));
            return 3;
        } finally {
            renderer.close();
        }
    }

    // 持续处理消息、本地命令和权限决定
    private static int inputLoop(
            TuiConnection connection, TuiModel model, TuiRenderer renderer, String initialSession)
            throws Exception {
        var input = TerminalInput.open();
        String sessionId = initialSession;
        List<SessionInfo> sessions = List.of();
        List<SkillInfo> skills = List.of();
        String lastMessage = "";
        while (true) {
            DaemonClient daemon = connection.clientOrNull();
            TuiModel.Snapshot beforeInput = model.snapshot();
            renderer.render(beforeInput);
            String line = readMultiline(input);
            if (line == null || line.strip().equalsIgnoreCase("/exit")) {
                return 0;
            }
            PermissionRequestedEvent permission = beforeInput.permission();
            if (permission != null) {
                if (daemon == null) {
                    model.notice("permission remains pending until reconnect completes");
                    continue;
                }
                if (respondPermission(daemon, permission, line, model)) {
                    model.takePermission(permission.toolUseId());
                    continue;
                }
            }
            if (line.isBlank()) {
                continue;
            }
            if (daemon == null) {
                model.notice("daemon is reconnecting; only /exit is available");
                continue;
            }
            if (line.equals("/help")) {
                model.notice("/skills, /skill <n> [args], /sessions, /resume <n|id>, "
                        + "/new [title], /history, /compact [focus], /tools, /again, /exit");
            } else if (line.equals("/skills")) {
                skills = daemon.listSkills().join().skills();
                for (int index = 0; index < skills.size(); index++) {
                    SkillInfo skill = skills.get(index);
                    model.notice((index + 1) + ". /" + skill.name() + "  " + skill.description());
                }
            } else if (line.equals("/sessions")) {
                sessions = daemon.listSessions().join().sessions();
                for (int index = 0; index < sessions.size(); index++) {
                    SessionInfo session = sessions.get(index);
                    model.notice((index + 1) + ". " + session.sessionId() + "  "
                            + session.status().wireValue() + "  " + session.title());
                }
            } else if (line.startsWith("/resume ")) {
                sessionId = selectSession(line.substring(8).strip(), sessions);
                daemon.getHistory(sessionId).join();
                model.session(sessionId);
                connection.attachSession(sessionId);
                model.notice("session resumed");
            } else if (line.equals("/new") || line.startsWith("/new ")) {
                String title = line.length() > 4 ? line.substring(4).strip() : "";
                sessionId = daemon.createChatSession(title).join().sessionId();
                model.session(sessionId);
                connection.attachSession(sessionId);
                model.notice("new session created");
            } else if (line.equals("/history")) {
                daemon.getHistory(sessionId).join().messages().forEach(message ->
                        model.notice(message.path("role").asText() + ": "
                                + message.path("content").toString()));
            } else if (line.equals("/compact") || line.startsWith("/compact ")) {
                String focus = line.length() > 8 ? line.substring(8).strip() : "";
                var result = daemon.compact(sessionId, focus).join();
                model.notice("compacted; saved " + result.savedTokens() + " tokens");
            } else if (line.equals("/tools")) {
                model.notice("tool details " + (model.toggleToolDetails() ? "expanded" : "collapsed"));
            } else if (line.equals("/again")) {
                if (lastMessage.isBlank()) {
                    model.notice("no previous message");
                    continue;
                }
                line = lastMessage;
                if (sendMessage(daemon, model, renderer, sessionId, line)) {
                    lastMessage = line;
                }
            } else if (line.startsWith("/skill ")) {
                String expanded = selectSkill(line.substring(7).strip(), skills);
                if (expanded == null) {
                    model.notice("run /skills first, then use /skill <number> [arguments]");
                    continue;
                }
                if (sendMessage(daemon, model, renderer, sessionId, expanded)) {
                    lastMessage = expanded;
                }
            } else {
                if (sendMessage(daemon, model, renderer, sessionId, line)) {
                    lastMessage = line;
                }
            }
        }
    }

    // 校验状态并异步发送一条 session 消息
    private static boolean sendMessage(
            DaemonClient daemon, TuiModel model, TuiRenderer renderer,
            String sessionId, String line) {
        if (!model.snapshot().connected()) {
            model.notice("cannot send while daemon is disconnected");
            return false;
        }
        if (model.busy()) {
            model.notice("wait for the current agent turn to finish");
            return false;
        }
        model.userMessage(line);
        daemon.sendMessage(sessionId, line).whenComplete((result, error) -> {
            model.requestCompleted();
            if (error != null) {
                model.notice("request failed: " + rootMessage(error));
            }
            renderer.render(model.snapshot());
        });
        return true;
    }

    // 读取以反斜杠续行的多行输入
    private static String readMultiline(BufferedReader input) throws java.io.IOException {
        String line = input.readLine();
        if (line == null) {
            return null;
        }
        var content = new StringBuilder();
        while (line.endsWith("\\")) {
            content.append(line, 0, line.length() - 1).append('\n');
            line = input.readLine();
            if (line == null) {
                return content.toString();
            }
        }
        return content.append(line).toString();
    }

    // 将 session 编号或原始标识解析为 session ID
    private static String selectSession(String selection, List<SessionInfo> sessions) {
        try {
            int index = Integer.parseInt(selection) - 1;
            if (index >= 0 && index < sessions.size()) {
                return sessions.get(index).sessionId();
            }
        } catch (NumberFormatException ignored) {
            return selection;
        }
        throw new IllegalArgumentException("session number out of range");
    }

    // 将 skill 编号和剩余参数展开为 slash 输入
    private static String selectSkill(String selection, List<SkillInfo> skills) {
        int space = selection.indexOf(' ');
        String number = space < 0 ? selection : selection.substring(0, space);
        try {
            int index = Integer.parseInt(number) - 1;
            if (index < 0 || index >= skills.size()) {
                return null;
            }
            String arguments = space < 0 ? "" : selection.substring(space + 1).strip();
            return "/" + skills.get(index).name() + (arguments.isEmpty() ? "" : " " + arguments);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    // 将单字符权限输入映射为协议决定
    private static boolean respondPermission(
            DaemonClient daemon,
            PermissionRequestedEvent permission,
            String answer,
            TuiModel model) {
        String decision = switch (answer.strip().toLowerCase()) {
            case "y", "yes" -> "allow_once";
            case "a", "always" -> "always_allow";
            default -> "deny_once";
        };
        try {
            boolean accepted = daemon.respondPermission(permission.toolUseId(), decision).join().ok();
            model.notice(accepted
                    ? "permission response: " + decision
                    : "permission request is no longer pending");
            return true;
        } catch (RuntimeException error) {
            model.notice("permission response failed; waiting for reconnect");
            return false;
        }
    }

    // 解析可选的会话恢复参数
    private static String parseSession(String[] args) {
        if (args.length == 0) {
            return "";
        }
        if (args.length == 2 && args[0].equals("--session") && !args[1].isBlank()) {
            return args[1];
        }
        throw new IllegalArgumentException("usage: klaude-tui [--session <session-id>]");
    }

    // 返回异常链最深处的用户可读消息
    static String rootMessage(Throwable error) {
        Throwable current = error instanceof CompletionException && error.getCause() != null
                ? error.getCause() : error;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }
}
