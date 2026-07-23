package io.klaude.cli;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

final class TerminalMarkdownTest {
    // 功能：在纯文本终端中清理常用 Markdown 标记
    // 设计：覆盖标题、列表、引用、粗体和行内代码的最小语法集
    @Test
    void rendersPlainMarkdown() {
        assertThat(TerminalMarkdown.render("# title", false)).isEqualTo("TITLE");
        assertThat(TerminalMarkdown.render("- item", false)).isEqualTo("• item");
        assertThat(TerminalMarkdown.render("> quote", false)).isEqualTo("│ quote");
        assertThat(TerminalMarkdown.render("**bold** and `code`", false))
                .isEqualTo("bold and code");
    }
}
