package io.klaude.cli;

final class TerminalMarkdown {
    private static final String BOLD = "\033[1m";
    private static final String CYAN = "\033[36m";
    private static final String RESET = "\033[0m";

    // 禁止实例化 Markdown 格式化工具
    private TerminalMarkdown() {
    }

    // 将一行常用 Markdown 转换为 ANSI 或干净文本
    static String render(String line, boolean ansi) {
        String value = line;
        if (value.startsWith("### ")) {
            value = heading(value.substring(4), ansi);
        } else if (value.startsWith("## ")) {
            value = heading(value.substring(3), ansi);
        } else if (value.startsWith("# ")) {
            value = heading(value.substring(2), ansi);
        } else if (value.startsWith("- ") || value.startsWith("* ")) {
            value = "• " + value.substring(2);
        } else if (value.startsWith("> ")) {
            value = "│ " + value.substring(2);
        }
        value = value.replace("**", ansi ? BOLD : "");
        value = value.replace("`", ansi ? CYAN : "");
        return ansi && (value.contains(BOLD) || value.contains(CYAN)) ? value + RESET : value;
    }

    // 格式化 Markdown 标题
    private static String heading(String text, boolean ansi) {
        return ansi ? BOLD + text + RESET : text.toUpperCase();
    }
}
