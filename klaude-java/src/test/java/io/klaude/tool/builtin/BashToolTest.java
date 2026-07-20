package io.klaude.tool.builtin;

import static org.assertj.core.api.Assertions.assertThat;

import io.klaude.protocol.ProtocolJson;
import io.klaude.tool.ToolContext;
import io.klaude.tool.ToolErrorType;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class BashToolTest {
    // 功能：验证 bash 在显式 workspace 内执行命令并返回合并输出
    // 设计：命令写入相对文件并输出 marker，观察文件位置与 ToolResult
    @Test
    void executesCommandInsideWorkspace(@TempDir Path temp) throws Exception {
        var result = new BashTool(temp).execute(
                        new ToolContext(temp, "session-001", "run-001", "tool-001"),
                        ProtocolJson.mapper().createObjectNode()
                                .put("command", "echo workspace>created.txt && echo hello"))
                .toCompletableFuture().get();

        assertThat(result.isError()).isFalse();
        assertThat(result.content()).contains("hello");
        assertThat(Files.readString(temp.resolve("created.txt"), StandardCharsets.UTF_8))
                .contains("workspace");
    }

    // 功能：验证 bash 将输出限制为 64 KiB 并显式标记截断
    // 设计：运行 Java fixture 生成 70 KiB ASCII，观察返回字节数和 truncated 后缀
    @Test
    void truncatesOutputAfterSixtyFourKib(@TempDir Path temp) throws Exception {
        var result = new BashTool(temp).execute(
                        new ToolContext(temp, "session-001", "run-001", "tool-001"),
                        ProtocolJson.mapper().createObjectNode()
                                .put("command", processCommand("71680")))
                .toCompletableFuture().get();

        assertThat(result.isError()).isFalse();
        assertThat(result.content()).endsWith("\n[truncated]");
        assertThat(result.content().getBytes(StandardCharsets.UTF_8))
                .hasSize(64 * 1024 + "\n[truncated]".length());
    }

    // 功能：验证 bash 在参数指定的秒数后终止子进程并返回 timeout
    // 设计：运行睡眠 10 秒的 Java fixture，设置 1 秒上限并观察结果分类与耗时
    @Test
    void terminatesCommandAfterRequestedTimeout(@TempDir Path temp) throws Exception {
        long started = System.nanoTime();
        var result = new BashTool(temp).execute(
                        new ToolContext(temp, "session-001", "run-001", "tool-001"),
                        ProtocolJson.mapper().createObjectNode()
                                .put("command", processCommand("sleep", "10000"))
                                .put("timeout", 1))
                .toCompletableFuture().get();
        long elapsedMillis = java.time.Duration.ofNanos(System.nanoTime() - started).toMillis();

        assertThat(result.isError()).isTrue();
        assertThat(result.errorType()).isEqualTo(ToolErrorType.TIMEOUT);
        assertThat(result.content()).isEqualTo("[timeout after 1s]");
        assertThat(elapsedMillis).isLessThan(4_000L);
    }

    // 构造运行隔离 Java fixture 的平台 shell 命令
    private static String processCommand(String... args) throws Exception {
        Path java = Path.of(System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java");
        Path classes = Path.of(ProcessFixture.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI());
        return quote(java.toString()) + " -cp " + quote(classes.toString()) + " "
                + ProcessFixture.class.getName() + " " + String.join(" ", args);
    }

    // 为 shell 参数添加平台适用的双引号
    private static String quote(String value) {
        return "\"" + value.replace("\"", "\\\"") + "\"";
    }

    // 判断当前是否为 Windows
    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win");
    }
}
