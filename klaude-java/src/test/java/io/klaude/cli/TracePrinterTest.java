package io.klaude.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class TracePrinterTest {
    // 功能：以紧凑时间线输出有效 trace 并忽略损坏尾行
    // 设计：写入一条 command 和半条 JSON，验证容错读取与摘要字段
    @Test
    void printsValidRecordsAndSkipsBrokenTail(@TempDir Path temp) throws Exception {
        Path trace = temp.resolve("daemon.jsonl");
        Files.writeString(trace, """
                {"ts":"2026-07-20T00:00:00Z","direction":"CLIENT→CORE","layer":"ipc","kind":"command","data":{"method":"core.ping"}}
                {broken
                """);
        var output = new ByteArrayOutputStream();
        var errors = new ByteArrayOutputStream();

        int exitCode = TracePrinter.print(
                trace,
                new PrintStream(output, true, StandardCharsets.UTF_8),
                new PrintStream(errors, true, StandardCharsets.UTF_8));

        assertThat(exitCode).isZero();
        assertThat(output.toString(StandardCharsets.UTF_8)).contains("CLIENT->CORE", "core.ping");
        assertThat(errors.toString(StandardCharsets.UTF_8)).isEmpty();
    }
}
