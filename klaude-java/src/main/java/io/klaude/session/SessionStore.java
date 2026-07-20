package io.klaude.session;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.klaude.protocol.ProtocolJson;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class SessionStore {
    private static final int TOOL_RESULT_LIMIT = 8_000;
    private static final int TOOL_RESULT_KEEP = 4_000;
    private static final DateTimeFormatter BACKUP_TIME =
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss").withZone(ZoneOffset.UTC);
    private final Path root;
    private final Clock clock;
    private final int toolResultLimit;
    private final int toolResultKeep;

    // 初始化隔离的 session 根目录与时间来源
    public SessionStore(Path root, Clock clock) throws IOException {
        this(root, clock, TOOL_RESULT_LIMIT, TOOL_RESULT_KEEP);
    }

    // 初始化带可配置 tool-result 截断边界的 session store
    public SessionStore(
            Path root, Clock clock, int toolResultLimit, int toolResultKeep) throws IOException {
        if (toolResultLimit < 0 || toolResultKeep < 0 || toolResultKeep > toolResultLimit) {
            throw new IllegalArgumentException("tool result limits require 0 <= keep <= limit");
        }
        this.root = root.toAbsolutePath().normalize();
        this.clock = clock;
        this.toolResultLimit = toolResultLimit;
        this.toolResultKeep = toolResultKeep;
        Files.createDirectories(this.root);
    }

    // 从 UTF-8 meta.json 读取不可变 session metadata
    public Session readMeta(String sessionId) throws IOException {
        return ProtocolJson.mapper().readValue(sessionDirectory(sessionId).resolve("meta.json").toFile(), Session.class);
    }

    // 将不可变 session metadata 以 UTF-8 JSON 写入 meta.json
    public void writeMeta(Session session) throws IOException {
        Path directory = sessionDirectory(session.id());
        Files.createDirectories(directory);
        String json = ProtocolJson.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(session);
        Files.writeString(directory.resolve("meta.json"), json + System.lineSeparator(), StandardCharsets.UTF_8);
    }

    // 将一条带时间与可选 run ID 的模型消息完整追加到 UTF-8 JSONL
    public void appendMessage(
            String sessionId, String role, JsonNode content, String runId) throws IOException {
        Path directory = sessionDirectory(sessionId);
        Files.createDirectories(directory);
        ObjectNode row = ProtocolJson.mapper().createObjectNode();
        row.put("ts", Instant.now(clock).toString());
        row.put("role", role);
        row.set("content", content);
        if (runId != null) {
            row.put("run_id", runId);
        }
        String line = ProtocolJson.mapper().writeValueAsString(row) + System.lineSeparator();
        Files.writeString(
                directory.resolve("thread.jsonl"),
                line,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
    }

    // 从 UTF-8 thread.jsonl 读取可提交给模型的 role/content JSON trees
    public List<ObjectNode> readMessages(String sessionId) throws IOException {
        Path path = sessionDirectory(sessionId).resolve("thread.jsonl");
        if (!Files.exists(path)) {
            return List.of();
        }
        List<ObjectNode> messages = new ArrayList<>();
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            JsonNode row;
            try {
                row = ProtocolJson.mapper().readTree(line);
            } catch (JsonProcessingException ignored) {
                continue;
            }
            String role = row.path("role").asText();
            if (!role.equals("user") && !role.equals("assistant")) {
                continue;
            }
            ObjectNode message = ProtocolJson.mapper().createObjectNode();
            message.put("role", role);
            message.set("content", row.has("content")
                    ? row.get("content")
                    : ProtocolJson.mapper().getNodeFactory().textNode(""));
            messages.add(message);
        }
        return truncateToolResults(trimOrphanToolUse(messages));
    }

    // 裁掉尾部存在未配对 tool_use 的消息并保留最后一个安全前缀
    private static List<ObjectNode> trimOrphanToolUse(List<ObjectNode> messages) {
        Set<String> pending = new HashSet<>();
        int lastBalanced = 0;
        for (int index = 0; index < messages.size(); index++) {
            ObjectNode message = messages.get(index);
            JsonNode content = message.get("content");
            if (content != null && content.isArray()) {
                for (JsonNode block : content) {
                    if (message.path("role").asText().equals("assistant")
                            && block.path("type").asText().equals("tool_use")) {
                        pending.add(block.path("id").asText());
                    } else if (message.path("role").asText().equals("user")
                            && block.path("type").asText().equals("tool_result")) {
                        pending.remove(block.path("tool_use_id").asText());
                    }
                }
            }
            if (pending.isEmpty()) {
                lastBalanced = index + 1;
            }
        }
        return pending.isEmpty()
                ? List.copyOf(messages)
                : List.copyOf(messages.subList(0, lastBalanced));
    }

    // 截断 user 消息中的超长字符串 tool_result 并保留精确 Unicode 字符计数
    private List<ObjectNode> truncateToolResults(List<ObjectNode> messages) {
        List<ObjectNode> result = new ArrayList<>();
        for (ObjectNode original : messages) {
            ObjectNode message = original.deepCopy();
            JsonNode content = message.get("content");
            if (message.path("role").asText().equals("user") && content != null && content.isArray()) {
                for (JsonNode block : content) {
                    JsonNode textNode = block.get("content");
                    if (block.path("type").asText().equals("tool_result")
                            && textNode != null
                            && textNode.isTextual()) {
                        String text = textNode.textValue();
                        int length = text.codePointCount(0, text.length());
                        if (length > toolResultLimit) {
                            int end = text.offsetByCodePoints(0, toolResultKeep);
                            ((ObjectNode) block).put(
                                    "content",
                                    text.substring(0, end)
                                            + "\n[... "
                                            + (length - toolResultKeep)
                                            + " chars omitted. Full output in run events.]");
                        }
                    }
                }
            }
            result.add(message);
        }
        return List.copyOf(result);
    }

    // 先准备完整临时 JSONL 和原文件备份，再原子替换压缩后的 thread
    public void writeCompacted(String sessionId, List<ObjectNode> messages) throws IOException {
        Path directory = sessionDirectory(sessionId);
        Files.createDirectories(directory);
        Path thread = directory.resolve("thread.jsonl");
        Path temporary = directory.resolve("thread.jsonl.tmp");
        String timestamp = Instant.now(clock).toString();
        StringBuilder jsonl = new StringBuilder();
        for (ObjectNode message : messages) {
            String role = message.path("role").asText();
            if ((!role.equals("user") && !role.equals("assistant")) || !message.has("content")) {
                throw new IllegalArgumentException("compacted message requires role and content");
            }
            ObjectNode row = ProtocolJson.mapper().createObjectNode();
            row.put("ts", timestamp);
            row.put("role", role);
            row.set("content", message.get("content"));
            jsonl.append(ProtocolJson.mapper().writeValueAsString(row)).append('\n');
        }
        try {
            Files.writeString(
                    temporary,
                    jsonl,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING);
            if (Files.exists(thread)) {
                Path backup = directory.resolve(
                        "thread_" + BACKUP_TIME.format(Instant.now(clock)) + ".jsonl.bak");
                Files.copy(thread, backup);
            }
            Files.move(
                    temporary,
                    thread,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    // 读取完整 UTF-8 notes.md 并在文件不存在时返回空文本
    public String readNotes(String sessionId) throws IOException {
        Path path = sessionDirectory(sessionId).resolve("notes.md");
        return Files.exists(path) ? Files.readString(path, StandardCharsets.UTF_8) : "";
    }

    // 将一条带固定 UTC 时间和 run ID 的 Unicode 笔记追加到 notes.md
    public void appendNote(String sessionId, String content, String runId) throws IOException {
        Path directory = sessionDirectory(sessionId);
        Files.createDirectories(directory);
        String note = "## Note (" + Instant.now(clock) + ", " + runId + ")\n" + content + "\n\n";
        Files.writeString(
                directory.resolve("notes.md"),
                note,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
    }

    // 返回 session ID 对应的持久化目录
    public Path sessionDirectory(String sessionId) {
        return root.resolve(sessionId);
    }
}
