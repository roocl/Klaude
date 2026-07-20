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

public final class TraceStore {
    private final Path path;

    // 初始化一个显式 trace JSONL 路径
    public TraceStore(Path path) {
        this.path = path.toAbsolutePath().normalize();
    }

    // 将一个 trace object 作为完整 UTF-8 JSONL 行按调用顺序追加
    public synchronized void append(ObjectNode record) throws IOException {
        Files.createDirectories(path.getParent());
        String line = ProtocolJson.mapper().writeValueAsString(record) + "\n";
        Files.writeString(
                path,
                line,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
    }

    // 从 UTF-8 JSONL 读取全部 trace object trees
    public List<ObjectNode> readAll() throws IOException {
        if (!Files.exists(path)) {
            return List.of();
        }
        List<ObjectNode> records = new ArrayList<>();
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            JsonNode record;
            try {
                record = ProtocolJson.mapper().readTree(line);
            } catch (JsonProcessingException ignored) {
                continue;
            }
            if (record.isObject()) {
                records.add((ObjectNode) record);
            }
        }
        return List.copyOf(records);
    }
}
