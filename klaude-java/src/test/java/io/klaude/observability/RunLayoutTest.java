package io.klaude.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class RunLayoutTest {
    // 功能：验证注入时间与随机源生成确定 run ID 并创建标准事件目录布局
    // 设计：固定 UTC Clock 和十六进制 supplier，调用公开 createRun 后检查 ID、目录与 events 路径
    @Test
    void createsDeterministicRunLayout(@TempDir Path temp) throws Exception {
        Clock clock = Clock.fixed(Instant.parse("2026-07-19T10:15:30Z"), ZoneOffset.UTC);
        RunLayout layout = new RunLayout(temp.resolve("runs"), clock, () -> "abcdef123456");

        String runId = layout.createRun();

        assertThat(runId).isEqualTo("20260719-101530-abcdef");
        assertThat(Files.isDirectory(layout.runDirectory(runId))).isTrue();
        assertThat(layout.eventsFile(runId))
                .isEqualTo(temp.resolve("runs/20260719-101530-abcdef/events.jsonl").toAbsolutePath());
    }
}
