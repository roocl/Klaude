package io.klaude.observability;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.function.Supplier;

public final class RunLayout {
    private static final DateTimeFormatter RUN_TIME =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);
    private final Path root;
    private final Clock clock;
    private final Supplier<String> idSupplier;

    // 初始化 run 根目录、时间来源与随机 ID 来源
    public RunLayout(Path root, Clock clock, Supplier<String> idSupplier) {
        this.root = root.toAbsolutePath().normalize();
        this.clock = clock;
        this.idSupplier = idSupplier;
    }

    // 生成 run ID、创建对应目录并返回 ID
    public String createRun() throws IOException {
        String suffix = idSupplier.get();
        if (suffix == null || suffix.length() < 6) {
            throw new IllegalArgumentException("run ID supplier must provide at least six characters");
        }
        String runId = RUN_TIME.format(Instant.now(clock)) + "-" + suffix.substring(0, 6);
        Files.createDirectories(runDirectory(runId));
        return runId;
    }

    // 返回指定 run ID 的目录路径
    public Path runDirectory(String runId) {
        return root.resolve(runId);
    }

    // 返回指定 run ID 的 events.jsonl 路径
    public Path eventsFile(String runId) {
        return runDirectory(runId).resolve("events.jsonl");
    }
}
