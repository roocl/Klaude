package io.klaude.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.klaude.protocol.ProtocolJson;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

final class InstalledDistributionTest {
    // 功能：验证安装后的 Windows 发行包可从任意工作目录启动并响应 ping
    // 设计：临时 cwd/home 启动真实 bat，以 ephemeral port 完成 socket 往返并验证端口释放
    @Test
    @Timeout(30)
    @EnabledOnOs(OS.WINDOWS)
    void installedWindowsDistributionStartsOutsideSourceTree(@TempDir Path temp) throws Exception {
        Path distribution = Path.of(System.getProperty("klaude.distribution"));
        Path launcher = distribution.resolve("bin/klaude-core-java.bat");
        verifyInstalledDistribution(launcher, temp);
        assertThat(distribution.resolve("bin/klaude.bat")).isRegularFile();
        assertThat(distribution.resolve("bin/klaude-tui.bat")).isRegularFile();
    }

    // 功能：验证安装后的 Unix 发行包可从任意工作目录启动并响应 ping
    // 设计：临时 cwd/home 启动真实 shell launcher，以 ephemeral port 验证往返及关闭
    @Test
    @Timeout(30)
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void installedUnixDistributionStartsOutsideSourceTree(@TempDir Path temp) throws Exception {
        Path distribution = Path.of(System.getProperty("klaude.distribution"));
        Path launcher = distribution.resolve("bin/klaude-core-java");
        verifyInstalledDistribution(launcher, temp);
        assertThat(distribution.resolve("bin/klaude")).isRegularFile();
        assertThat(distribution.resolve("bin/klaude-tui")).isRegularFile();
    }

    // 启动指定平台 launcher 并验证正式 JSON-RPC ping 与 listener 回收
    private static void verifyInstalledDistribution(Path launcher, Path temp) throws Exception {
        assertThat(launcher).isRegularFile();
        Path home = temp.resolve("home");
        Path workspace = temp.resolve("workspace");
        Files.createDirectories(home);
        Files.createDirectories(workspace);
        ProcessBuilder builder = new ProcessBuilder("cmd", "/c", launcher.toString());
        builder.directory(workspace.toFile());
        builder.redirectErrorStream(true);
        builder.environment().put("JAVA_HOME", System.getProperty("java.home"));
        builder.environment().put("KLAUDE_JAVA_HOME", System.getProperty("java.home"));
        builder.environment().put("JAVA_OPTS", "-Duser.home=" + home);
        builder.environment().put("KLAUDE_PORT", "0");
        Process process = builder.start();
        int port = -1;
        try (var reader = new BufferedReader(new InputStreamReader(
                process.getInputStream(), StandardCharsets.UTF_8))) {
            String line = CompletableFuture.supplyAsync(() -> awaitListeningLine(reader))
                    .get(10, TimeUnit.SECONDS);
            assertThat(line).startsWith("Klaude daemon listening at 127.0.0.1:");
            port = Integer.parseInt(line.substring(line.lastIndexOf(':') + 1));
            try (Socket socket = new Socket("127.0.0.1", port);
                 var writer = new OutputStreamWriter(
                         socket.getOutputStream(), StandardCharsets.UTF_8);
                 var response = new BufferedReader(new InputStreamReader(
                         socket.getInputStream(), StandardCharsets.UTF_8))) {
                socket.setSoTimeout(Math.toIntExact(Duration.ofSeconds(5).toMillis()));
                writer.write("{\"jsonrpc\":\"2.0\",\"id\":\"ping\","
                        + "\"method\":\"core.ping\","
                        + "\"params\":{\"type\":\"core.ping\","
                        + "\"client\":\"distribution-test\"}}\n");
                writer.flush();
                var reply = ProtocolJson.mapper().readTree(response.readLine());
                assertThat(reply.path("result").path("server_version").asText())
                        .withFailMessage("unexpected ping response: %s", reply)
                        .isEqualTo("0.1.0");
            }
            verifyCliPing(launcher, workspace, home, port);
            verifyCliSkillDiscovery(launcher, workspace, home, port);
            verifyTuiSkillDiscovery(launcher, workspace, home, port);
            verifyCliTrace(launcher, workspace, home);
        } finally {
            terminate(process);
        }
        assertThat(awaitListenerClosed(port, Duration.ofSeconds(5))).isTrue();
    }

    // 从安装目录执行独立 CLI 进程并验证真实 daemon ping
    private static void verifyCliPing(
            Path daemonLauncher, Path workspace, Path home, int port) throws Exception {
        String cliName = daemonLauncher.getFileName().toString().endsWith(".bat")
                ? "klaude.bat"
                : "klaude";
        Path cli = daemonLauncher.resolveSibling(cliName);
        assertThat(cli).isRegularFile();
        ProcessBuilder builder = cliName.endsWith(".bat")
                ? new ProcessBuilder("cmd", "/c", cli.toString(), "ping")
                : new ProcessBuilder(cli.toString(), "ping");
        builder.directory(workspace.toFile());
        builder.redirectErrorStream(true);
        Files.writeString(
                workspace.resolve(".env"),
                "KLAUDE_JAVA_HOME=" + System.getProperty("java.home") + "\n",
                StandardCharsets.UTF_8);
        builder.environment().put("JAVA_HOME", workspace.resolve("invalid-java").toString());
        builder.environment().remove("KLAUDE_JAVA_HOME");
        builder.environment().put("JAVA_OPTS", "-Duser.home=" + home);
        builder.environment().put("KLAUDE_PORT", Integer.toString(port));
        Process process = builder.start();
        assertThat(process.waitFor(10, TimeUnit.SECONDS)).isTrue();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.exitValue()).withFailMessage(output).isZero();
        assertThat(output).contains("pong server=0.1.0 uptime=");
    }

    // 通过安装包 chat 输入列出生产内置 skills 并退出
    private static void verifyCliSkillDiscovery(
            Path daemonLauncher, Path workspace, Path home, int port) throws Exception {
        String cliName = daemonLauncher.getFileName().toString().endsWith(".bat")
                ? "klaude.bat"
                : "klaude";
        Path cli = daemonLauncher.resolveSibling(cliName);
        ProcessBuilder builder = cliName.endsWith(".bat")
                ? new ProcessBuilder("cmd", "/c", cli.toString(), "chat")
                : new ProcessBuilder(cli.toString(), "chat");
        builder.directory(workspace.toFile());
        builder.redirectErrorStream(true);
        builder.environment().put("JAVA_HOME", System.getProperty("java.home"));
        builder.environment().put("JAVA_OPTS", "-Duser.home=" + home);
        builder.environment().put("KLAUDE_PORT", Integer.toString(port));
        Process process = builder.start();
        try (var input = new OutputStreamWriter(
                process.getOutputStream(), StandardCharsets.UTF_8)) {
            input.write("/skills\n/exit\n");
        }
        assertThat(process.waitFor(10, TimeUnit.SECONDS)).isTrue();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.exitValue()).withFailMessage(output).isZero();
        assertThat(output).contains("Klaude chat", "/init  ");
    }

    // 使用安装包 CLI 读取 daemon 已写入的 IPC trace
    private static void verifyCliTrace(
            Path daemonLauncher, Path workspace, Path home) throws Exception {
        String cliName = daemonLauncher.getFileName().toString().endsWith(".bat")
                ? "klaude.bat"
                : "klaude";
        Path cli = daemonLauncher.resolveSibling(cliName);
        ProcessBuilder builder = cliName.endsWith(".bat")
                ? new ProcessBuilder("cmd", "/c", cli.toString(), "trace")
                : new ProcessBuilder(cli.toString(), "trace");
        builder.directory(workspace.toFile());
        builder.redirectErrorStream(true);
        builder.environment().put("JAVA_HOME", System.getProperty("java.home"));
        builder.environment().put("JAVA_OPTS", "-Duser.home=" + home);
        Process process = builder.start();
        assertThat(process.waitFor(10, TimeUnit.SECONDS)).isTrue();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.exitValue()).withFailMessage(output).isZero();
        assertThat(output).contains("CLIENT->CORE", "core.ping");
    }

    // 通过安装包 TUI 创建真实 session、列出 skills 并正常退出
    private static void verifyTuiSkillDiscovery(
            Path daemonLauncher, Path workspace, Path home, int port) throws Exception {
        String tuiName = daemonLauncher.getFileName().toString().endsWith(".bat")
                ? "klaude-tui.bat"
                : "klaude-tui";
        Path tui = daemonLauncher.resolveSibling(tuiName);
        ProcessBuilder builder = tuiName.endsWith(".bat")
                ? new ProcessBuilder("cmd", "/c", tui.toString())
                : new ProcessBuilder(tui.toString());
        builder.directory(workspace.toFile());
        builder.redirectErrorStream(true);
        builder.environment().put("JAVA_HOME", System.getProperty("java.home"));
        builder.environment().put("JAVA_OPTS", "-Duser.home=" + home);
        builder.environment().put("KLAUDE_PORT", Integer.toString(port));
        builder.environment().put("COLUMNS", "80");
        builder.environment().put("LINES", "24");
        Process process = builder.start();
        try (var input = new OutputStreamWriter(
                process.getOutputStream(), StandardCharsets.UTF_8)) {
            input.write("/skills\n/exit\n");
        }
        assertThat(process.waitFor(10, TimeUnit.SECONDS)).isTrue();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.exitValue()).withFailMessage(output).isZero();
        assertThat(output).contains("Klaude TUI | session", "/init  ");
    }

    // 忽略启动警告并等待 daemon 输出监听地址
    private static String awaitListeningLine(BufferedReader reader) {
        try {
            StringBuilder output = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append('\n');
                if (line.startsWith("Klaude daemon listening at ")) {
                    return line;
                }
            }
            throw new IllegalStateException("launcher exited before listening:\n" + output);
        } catch (java.io.IOException error) {
            throw new java.util.concurrent.CompletionException(error);
        }
    }

    // 终止启动器及其全部后代并等待释放资源
    private static void terminate(Process process) throws InterruptedException {
        var descendants = process.descendants().toList().reversed();
        descendants.forEach(ProcessHandle::destroy);
        process.destroy();
        for (ProcessHandle descendant : descendants) {
            try {
                descendant.onExit().get(5, TimeUnit.SECONDS);
            } catch (java.util.concurrent.ExecutionException
                     | java.util.concurrent.TimeoutException error) {
                descendant.destroyForcibly();
                try {
                    descendant.onExit().get(5, TimeUnit.SECONDS);
                } catch (java.util.concurrent.ExecutionException
                         | java.util.concurrent.TimeoutException ignored) {
                    // The final port assertion reports any process that still owns the listener.
                }
            }
        }
        if (!process.waitFor(5, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
        }
    }

    // 检查指定端口是否仍存在活动 listener
    private static boolean canConnect(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 500);
            return true;
        } catch (java.io.IOException ignored) {
            return false;
        }
    }

    // 在有界时间内等待操作系统关闭 listener
    private static boolean awaitListenerClosed(int port, Duration timeout)
            throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (!canConnect(port)) {
                return true;
            }
            Thread.sleep(50);
        }
        return !canConnect(port);
    }
}
