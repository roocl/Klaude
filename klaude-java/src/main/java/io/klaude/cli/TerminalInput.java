package io.klaude.cli;

import java.io.BufferedReader;
import java.io.Console;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;

final class TerminalInput {
    // 禁止实例化终端输入工具类
    private TerminalInput() {
    }

    // 交互终端使用控制台字符集，重定向输入使用 UTF-8
    static BufferedReader open() {
        Console console = System.console();
        return select(console == null ? null : console.reader(), System.in);
    }

    // 选择控制台读取器或 UTF-8 后备输入流
    static BufferedReader select(Reader consoleReader, InputStream fallback) {
        Reader reader = consoleReader == null
                ? new InputStreamReader(fallback, StandardCharsets.UTF_8)
                : consoleReader;
        return reader instanceof BufferedReader buffered ? buffered : new BufferedReader(reader);
    }
}
