package io.klaude.tool.builtin;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.klaude.protocol.ProtocolJson;
import io.klaude.tool.Tool;
import io.klaude.tool.ToolContext;
import io.klaude.tool.ToolErrorType;
import io.klaude.tool.ToolResult;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public final class BashTool implements Tool {
    private static final int MAX_OUTPUT_BYTES = 64 * 1024;
    private final Path workspaceRoot;

    // 初始化显式 workspace root
    public BashTool(Path workspaceRoot) throws IOException {
        this.workspaceRoot = new WorkspacePaths(workspaceRoot).root();
    }

    // 返回工具名
    @Override
    public String name() {
        return "bash";
    }

    // 返回工具描述
    @Override
    public String description() {
        return "Execute a non-interactive shell command inside the workspace.";
    }

    // 返回 command 和 timeout 参数 schema
    @Override
    public ObjectNode inputSchema() {
        var mapper = ProtocolJson.mapper();
        var schema = mapper.createObjectNode().put("type", "object");
        var properties = mapper.createObjectNode();
        properties.set("command", mapper.createObjectNode().put("type", "string"));
        properties.set("timeout", mapper.createObjectNode()
                .put("type", "integer")
                .put("minimum", 1)
                .put("maximum", 120)
                .put("default", 60));
        schema.set("properties", properties);
        schema.set("required", mapper.createArrayNode().add("command"));
        return schema;
    }

    // 在 workspace shell 中执行命令并合并输出
    @Override
    public CompletionStage<ToolResult> execute(ToolContext context, ObjectNode params) {
        Process process = null;
        InputStream processOutput = null;
        try {
            process = new ProcessBuilder(shellCommand(params.path("command").asText()))
                    .directory(workspaceRoot.toFile())
                    .redirectErrorStream(true)
                    .start();
            process.getOutputStream().close();
            processOutput = process.getInputStream();
            var output = new BoundedOutput();
            var readError = new AtomicReference<IOException>();
            InputStream capturedOutput = processOutput;
            Thread reader = Thread.ofVirtual().name("bash-output").start(() -> {
                try (capturedOutput) {
                    output.readFrom(capturedOutput);
                } catch (IOException error) {
                    readError.set(error);
                }
            });
            int timeoutSeconds = params.path("timeout").asInt(60);
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                terminate(process);
                processOutput.close();
                reader.join(1_000);
                return CompletableFuture.completedFuture(ToolResult.failure(
                        "[timeout after " + timeoutSeconds + "s]",
                        ToolErrorType.TIMEOUT));
            }
            reader.join();
            if (readError.get() != null) {
                throw readError.get();
            }
            int exitCode = process.exitValue();
            String content = output.content();
            if (exitCode != 0) {
                return CompletableFuture.completedFuture(ToolResult.failure(
                        "[exit " + exitCode + "]\n" + content,
                        ToolErrorType.RUNTIME_ERROR));
            }
            return CompletableFuture.completedFuture(
                    ToolResult.success(content.isEmpty() ? "[no output]" : content));
        } catch (Exception error) {
            if (process != null) {
                terminate(process);
            }
            if (error instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return CompletableFuture.completedFuture(
                    ToolResult.failure(error.getMessage(), ToolErrorType.RUNTIME_ERROR));
        } finally {
            if (processOutput != null) {
                try {
                    processOutput.close();
                } catch (IOException ignored) {
                    // Process completion already determines the tool result.
                }
            }
        }
    }

    // 强制终止 shell 及其已发现的子进程
    private static void terminate(Process process) {
        if (isWindows()) {
            terminateWindowsTree(process.pid());
        }
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        try {
            process.waitFor(200, TimeUnit.MILLISECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
    }

    // 使用 Windows 系统工具强制终止完整进程树
    private static void terminateWindowsTree(long pid) {
        Process taskkill = null;
        try {
            taskkill = new ProcessBuilder(
                    "taskkill.exe", "/PID", Long.toString(pid), "/T", "/F")
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            taskkill.getOutputStream().close();
            taskkill.waitFor(1, TimeUnit.SECONDS);
        } catch (IOException error) {
            // Direct ProcessHandle termination remains the fallback.
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        } finally {
            if (taskkill != null && taskkill.isAlive()) {
                taskkill.destroyForcibly();
            }
        }
    }

    // 根据当前操作系统构造平台 shell 命令
    private static List<String> shellCommand(String command) {
        if (isWindows()) {
            return List.of("cmd.exe", "/d", "/s", "/c", command);
        }
        return List.of("/bin/sh", "-c", command);
    }

    // 判断当前是否为 Windows 平台
    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
    }

    private static final class BoundedOutput {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream(MAX_OUTPUT_BYTES);
        private boolean truncated;

        // 持续排空输出流但仅保留上限内字节
        private void readFrom(InputStream stream) throws IOException {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = stream.read(buffer)) != -1) {
                int remaining = MAX_OUTPUT_BYTES - bytes.size();
                int retained = Math.min(count, Math.max(remaining, 0));
                if (retained > 0) {
                    bytes.write(buffer, 0, retained);
                }
                truncated |= retained < count;
            }
        }

        // 将保留字节解码为 UTF-8 并标记截断
        private String content() {
            String content = bytes.toString(StandardCharsets.UTF_8);
            return truncated ? content + "\n[truncated]" : content;
        }
    }
}
