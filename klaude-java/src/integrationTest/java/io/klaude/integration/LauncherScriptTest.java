package io.klaude.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

final class LauncherScriptTest {
    // 功能：验证 Windows launcher 默认选择 Java 发行入口
    // 设计：执行公开 PrintCommand 模式并观察选择结果，不启动长期 daemon
    @Test
    @EnabledOnOs(OS.WINDOWS)
    void selectsJavaByDefault() throws Exception {
        Process process = windowsLauncher();

        assertThat(process.waitFor(10, TimeUnit.SECONDS)).isTrue();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.exitValue()).isZero();
        assertThat(output).contains("runtime=java", "java_home=", "klaude-core-java.bat");
    }

    // 功能：验证 Unix launcher 默认选择 Java 发行入口
    // 设计：通过 sh 执行公开 print-command 模式，不启动长期 daemon
    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void selectsJavaByDefaultOnUnix() throws Exception {
        Process process = unixLauncher();

        assertThat(process.waitFor(10, TimeUnit.SECONDS)).isTrue();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.exitValue()).isZero();
        assertThat(output).contains("runtime=java", "klaude-core-java");
    }

    // 创建一个隔离的 PowerShell launcher process
    private static Process windowsLauncher() throws java.io.IOException {
        Path root = Path.of(System.getProperty("klaude.projectRoot"));
        ProcessBuilder builder = new ProcessBuilder(
                "powershell",
                "-NoProfile",
                "-ExecutionPolicy", "Bypass",
                "-File", root.resolve("scripts/start-core.ps1").toString(),
                "-PrintCommand");
        builder.redirectErrorStream(true);
        return builder.start();
    }

    // 创建一个隔离的 Unix shell launcher process
    private static Process unixLauncher() throws java.io.IOException {
        Path root = Path.of(System.getProperty("klaude.projectRoot"));
        ProcessBuilder builder = new ProcessBuilder(
                "sh", root.resolve("scripts/start-core.sh").toString(), "--print-command");
        builder.redirectErrorStream(true);
        return builder.start();
    }
}
