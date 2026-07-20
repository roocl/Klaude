package io.klaude.observability;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.klaude.protocol.ProtocolJson;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

public final class EventStore {
    private final Path path;

    // 初始化一个显式 events JSONL 路径
    public EventStore(Path path) {
        this.path = path.toAbsolutePath().normalize();
    }

    // 将一个 event object 作为完整 UTF-8 JSONL 行追加并刷新
    public synchronized void append(ObjectNode event) throws IOException {
        Files.createDirectories(path.getParent());
        String line = ProtocolJson.mapper().writeValueAsString(event) + "\n";
        Files.writeString(
                path,
                line,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND,
                StandardOpenOption.SYNC);
    }

    // 读取全部有效 event object 并跳过空行、坏 JSON 和非 object 行
    public List<ObjectNode> readAll() throws IOException {
        if (!Files.exists(path)) {
            return List.of();
        }
        List<ObjectNode> events = new ArrayList<>();
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            JsonNode event;
            try {
                event = ProtocolJson.mapper().readTree(line);
            } catch (JsonProcessingException ignored) {
                continue;
            }
            if (event.isObject()) {
                events.add((ObjectNode) event);
            }
        }
        return List.copyOf(events);
    }
}
