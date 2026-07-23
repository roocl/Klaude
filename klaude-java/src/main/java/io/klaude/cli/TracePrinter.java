package io.klaude.cli;

import io.klaude.protocol.ProtocolJson;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

final class TracePrinter {
    // 禁止实例化 trace 输出工具类
    private TracePrinter() {
    }

    // 容错读取 trace JSONL 并输出紧凑时间线
    static int print(Path path, PrintStream output, PrintStream errors) {
        if (!Files.isRegularFile(path)) {
            errors.println("error: trace file not found: " + path);
            return 1;
        }
        try (var lines = Files.lines(path, StandardCharsets.UTF_8)) {
            lines.filter(line -> !line.isBlank()).forEach(line -> printLine(line, output));
            return 0;
        } catch (java.io.IOException error) {
            errors.println("error: cannot read trace: " + error.getMessage());
            return 1;
        }
    }

    // 将一条有效 trace 格式化并跳过损坏尾行
    private static void printLine(String line, PrintStream output) {
        try {
            var record = ProtocolJson.mapper().readTree(line);
            String summary = record.path("data").path("method").asText();
            if (summary.isBlank()) {
                summary = record.path("data").path("event_type").asText();
            }
            if (summary.isBlank()) {
                summary = record.path("kind").asText();
            }
            String direction = record.path("direction").asText()
                    .replace("→", "->")
                    .replace("←", "<-");
            output.println(record.path("ts").asText() + "  "
                    + direction + "  "
                    + record.path("layer").asText() + "  " + summary);
        } catch (java.io.IOException ignored) {
            // A partial final JSONL row is ignored so trace inspection remains available.
        }
    }
}
