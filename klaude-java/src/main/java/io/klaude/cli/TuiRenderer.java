package io.klaude.cli;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class TuiRenderer {
    private final PrintStream output;
    private final boolean ansi;
    private final int width;
    private final int height;

    // 绑定输出流并决定是否使用 ANSI 全屏刷新
    TuiRenderer(PrintStream output, boolean ansi, int width, int height) {
        this.output = output;
        this.ansi = ansi;
        this.width = Math.max(40, width);
        this.height = Math.max(12, height);
    }

    // 根据终端环境创建带安全默认尺寸的渲染器
    static TuiRenderer create(PrintStream output, boolean ansi, Map<String, String> environment) {
        return new TuiRenderer(output, ansi,
                dimension(environment.get("COLUMNS"), 100),
                dimension(environment.get("LINES"), 30));
    }

    // 绘制标题、对话、状态和当前输入提示
    synchronized void render(TuiModel.Snapshot view) {
        if (ansi) {
            output.print("\033[2J\033[H");
        }
        output.println("Klaude TUI | session " + view.sessionId());
        output.println("-".repeat(width));
        List<String> body = new ArrayList<>();
        for (String line : view.transcript()) {
            body.addAll(wrapMarkdown(line));
        }
        if (!view.tokenLine().isEmpty()) {
            body.addAll(wrap("AI: " + view.tokenLine()));
        }
        if (view.subagents().length > 0) {
            body.add("Subagents:");
            for (String child : view.subagents()) {
                body.add("  └─ " + child);
            }
        }
        int visibleLines = Math.max(1, height - 8 - (view.permission() == null ? 0 : 1));
        for (String line : body.subList(Math.max(0, body.size() - visibleLines), body.size())) {
            output.println(line);
        }
        output.println("-".repeat(width));
        output.printf("status: %s | run: %s | model: %s%n",
                view.status(), shortId(view.runId()), view.model());
        output.printf("tokens: %d in / %d out | context: %.1f%% | tools: %s | agents: %d%n",
                view.inputTokens(), view.outputTokens(), view.contextPct(),
                view.toolDetails() ? "expanded" : "collapsed", view.subagents().length);
        if (view.permission() != null) {
            output.println("Permission: " + view.permission().toolName() + " "
                    + view.permission().paramPreview());
            output.print("Allow? [y] once, [a] always, [n] deny > ");
        } else if (!view.connected()) {
            output.print("Daemon disconnected; /exit > ");
        } else if (view.busy()) {
            output.print("Agent running; local commands or /exit > ");
        } else {
            output.print("Message (/help for commands) > ");
        }
        output.flush();
    }

    // 将长文本按当前终端宽度切成可见行
    private List<String> wrap(String value) {
        if (value.isEmpty()) {
            return List.of("");
        }
        var lines = new ArrayList<String>();
        for (String source : value.split("\\R", -1)) {
            if (source.isEmpty()) {
                lines.add("");
            }
            for (int offset = 0; offset < source.length(); offset += width) {
                lines.add(source.substring(offset, Math.min(source.length(), offset + width)));
            }
        }
        return lines;
    }

    // 逐行应用基础 Markdown 样式后执行终端换行
    private List<String> wrapMarkdown(String value) {
        var lines = new ArrayList<String>();
        for (String line : value.split("\\R", -1)) {
            for (String segment : wrap(line)) {
                lines.add(TerminalMarkdown.render(segment, ansi));
            }
        }
        return lines;
    }

    // 缩短状态栏中的长协议标识
    private static String shortId(String value) {
        return value.length() <= 12 ? value : value.substring(0, 12);
    }

    // 解析正整数终端尺寸并在无效时使用默认值
    private static int dimension(String value, int fallback) {
        try {
            int parsed = Integer.parseInt(value == null ? "" : value);
            return parsed > 0 ? parsed : fallback;
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    // 离开全屏模式并恢复普通终端行
    synchronized void close() {
        if (ansi) {
            output.print("\033[2J\033[H");
        } else {
            output.println();
        }
        output.flush();
    }
}
