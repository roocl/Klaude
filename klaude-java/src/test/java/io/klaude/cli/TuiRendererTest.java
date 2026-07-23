package io.klaude.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class TuiRendererTest {
    // 功能：按照终端宽高换行并裁剪较早的对话内容
    // 设计：使用小尺寸无 ANSI 输出验证最新内容和状态栏仍然可见
    @Test
    void wrapsAndClipsToTerminalDimensions() {
        var bytes = new ByteArrayOutputStream();
        var renderer = TuiRenderer.create(
                new PrintStream(bytes, true, StandardCharsets.UTF_8), false,
                Map.of("COLUMNS", "40", "LINES", "12"));
        var model = new TuiModel();
        for (int index = 0; index < 10; index++) {
            model.notice("line-" + index + " " + "x".repeat(50));
        }

        renderer.render(model.snapshot());

        String output = bytes.toString(StandardCharsets.UTF_8);
        assertThat(output).contains("line-9", "status: starting").doesNotContain("line-0");
    }
}
