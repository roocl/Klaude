package io.klaude.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.klaude.protocol.ProtocolJson;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

final class OfflineDemoTest {
    // 功能：验证离线 demo 经真实 daemon IPC 完成 scripted run 并自行退出
    // 设计：独立 Java process 不提供 API key，解析其最终 JSON 摘要和事件序列
    @Test
    @Timeout(20)
    void completesWithoutModelApi() throws Exception {
        ProcessBuilder builder = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp",
                System.getProperty("java.class.path"),
                "io.klaude.daemon.OfflineDemoMain");
        builder.redirectErrorStream(true);
        builder.environment().remove("ANTHROPIC_API_KEY");
        Process process = builder.start();
        String lastLine = "";
        try (var reader = new BufferedReader(new InputStreamReader(
                process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lastLine = line;
            }
        }

        assertThat(process.waitFor(10, TimeUnit.SECONDS)).isTrue();
        assertThat(process.exitValue()).isZero();
        var summary = ProtocolJson.mapper().readTree(lastLine);
        assertThat(summary.path("status").asText()).isEqualTo("success");
        assertThat(summary.path("result").asText()).isEqualTo("offline demo complete");
        assertThat(summary.path("startup_ms").asLong()).isGreaterThanOrEqualTo(0);
        assertThat(summary.path("idle_heap_bytes").asLong()).isPositive();
        assertThat(summary.path("first_token_ms").asLong()).isGreaterThanOrEqualTo(0);
        assertThat(summary.path("full_run_ms").asLong()).isGreaterThanOrEqualTo(0);
        assertThat(summary.path("concurrent_sessions").asInt()).isEqualTo(4);
        assertThat(summary.path("concurrent_sessions_ms").asLong()).isGreaterThanOrEqualTo(0);
        assertThat(summary.path("error_recovery").asBoolean()).isTrue();
        assertThat(summary.path("events")).extracting(event -> event.path("type").asText())
                .containsExactly("run.started", "step.started", "step.finished", "run.finished");
    }
}
