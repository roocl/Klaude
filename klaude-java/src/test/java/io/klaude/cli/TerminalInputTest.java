package io.klaude.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

final class TerminalInputTest {
    // 功能：交互控制台输入保留中文字符
    // 设计：提供控制台读取器并验证它优先于后备字节流
    @Test
    void prefersConsoleReaderForInteractiveInput() throws Exception {
        var fallback = new ByteArrayInputStream("wrong".getBytes(StandardCharsets.UTF_8));

        var input = TerminalInput.select(new StringReader("记住数字114514\n"), fallback);

        assertThat(input.readLine()).isEqualTo("记住数字114514");
    }

    // 功能：重定向输入继续支持 UTF-8 中文
    // 设计：省略控制台读取器并通过 UTF-8 字节流模拟管道输入
    @Test
    void decodesRedirectedInputAsUtf8() throws Exception {
        var fallback = new ByteArrayInputStream("记住数字114514\n".getBytes(StandardCharsets.UTF_8));

        var input = TerminalInput.select(null, fallback);

        assertThat(input.readLine()).isEqualTo("记住数字114514");
    }
}
