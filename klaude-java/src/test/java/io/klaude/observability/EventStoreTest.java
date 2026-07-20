package io.klaude.observability;

import static org.assertj.core.api.Assertions.assertThat;

import io.klaude.protocol.ProtocolJson;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class EventStoreTest {
    // 功能：验证冻结 events 可读取，Unicode 新事件可追加且损坏行被跳过
    // 设计：复制公共 fixture 到临时 run 文件，注入坏行后通过公开 append/readAll 观察顺序与数量
    @Test
    void readsAppendsAndRecoversEventJsonl(@TempDir Path temp) throws Exception {
        Path source = fixturePath("/fixtures/events.jsonl");
        Path path = temp.resolve("runs/run-001/events.jsonl");
        Files.createDirectories(path.getParent());
        Files.copy(source, path);
        int fixtureCount = Files.readAllLines(source, StandardCharsets.UTF_8).size();
        Files.writeString(
                path,
                "{broken-json\n",
                StandardCharsets.UTF_8,
                StandardOpenOption.APPEND);
        EventStore store = new EventStore(path);
        var event = ProtocolJson.mapper().createObjectNode()
                .put("type", "log.line")
                .put("run_id", "run-001")
                .put("message", "Java 事件：你好");

        store.append(event);
        var events = store.readAll();

        assertThat(events).hasSize(fixtureCount + 1);
        assertThat(events.getLast()).isEqualTo(event);
    }

    // 将测试 classpath 中的 fixture 转换为文件系统路径
    private static Path fixturePath(String resource) throws Exception {
        URI uri = Objects.requireNonNull(EventStoreTest.class.getResource(resource)).toURI();
        return Path.of(uri);
    }
}
