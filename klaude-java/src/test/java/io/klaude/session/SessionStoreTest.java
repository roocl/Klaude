package io.klaude.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.klaude.protocol.ProtocolJson;
import io.klaude.protocol.SessionMode;
import io.klaude.protocol.SessionStatus;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class SessionStoreTest {
    // 功能：验证冻结的有效 metadata 与 thread fixture 可由 Java 完整读取
    // 设计：直接把公共 fixture 目录交给公开 store，并与冻结的 JSON messages tree 比较
    @Test
    void readsValidPhaseZeroSessionFixture() throws Exception {
        Path root = fixturePath("/fixtures/session");
        SessionStore store = new SessionStore(root, Clock.systemUTC());

        Session metadata = store.readMeta("valid");
        var expectedMessages = ProtocolJson.mapper().readTree(
                root.resolve("valid/expected-messages.json").toFile());
        JsonNode actualMessages = ProtocolJson.mapper().valueToTree(store.readMessages("valid"));

        assertThat(metadata).isEqualTo(new Session(
                "valid",
                SessionMode.CHAT,
                SessionStatus.WAITING_FOR_INPUT,
                "Unicode 会话",
                "2026-07-18T12:00:00Z",
                "2026-07-18T12:00:00Z",
                List.of("run-001")));
        assertThat(actualMessages).isEqualTo(expectedMessages);
    }

    // 功能：验证损坏 JSONL 行、空行和未知角色不会阻断其余 session 历史恢复
    // 设计：读取 broken fixture，并与仅含合法 user 消息的冻结结果比较
    @Test
    void skipsBrokenRowsAndUnknownRoles() throws Exception {
        Path root = fixturePath("/fixtures/session");
        SessionStore store = new SessionStore(root, Clock.systemUTC());
        JsonNode expected = ProtocolJson.mapper().readTree(
                root.resolve("broken/expected-messages.json").toFile());

        JsonNode actual = ProtocolJson.mapper().valueToTree(store.readMessages("broken"));

        assertThat(actual).isEqualTo(expected);
    }

    // 功能：验证尾部没有对应 tool_result 的 tool_use 会被裁到最后配平位置
    // 设计：读取 orphan fixture，并与只保留安全 user 前缀的冻结结果比较
    @Test
    void trimsOrphanToolUseTail() throws Exception {
        Path root = fixturePath("/fixtures/session");
        SessionStore store = new SessionStore(root, Clock.systemUTC());
        JsonNode expected = ProtocolJson.mapper().readTree(
                root.resolve("orphan/expected-messages.json").toFile());

        JsonNode actual = ProtocolJson.mapper().valueToTree(store.readMessages("orphan"));

        assertThat(actual).isEqualTo(expected);
    }

    // 功能：验证 Java 能以 UTF-8 写入并完整读回不可变 session metadata
    // 设计：在临时根目录写含中文标题与 run IDs 的 Session，再仅通过公开 readMeta 比较
    @Test
    void writesAndReadsUnicodeMetadata(@TempDir Path temp) throws Exception {
        Clock clock = Clock.fixed(Instant.parse("2026-07-19T10:15:30Z"), ZoneOffset.UTC);
        SessionStore store = new SessionStore(temp.resolve("sessions"), clock);
        Session expected = new Session(
                "session-001",
                SessionMode.CHAT,
                SessionStatus.ACTIVE,
                "中文标题",
                "2026-07-19T10:15:30Z",
                "2026-07-19T10:15:30Z",
                List.of("run-001"));

        store.writeMeta(expected);

        assertThat(store.readMeta("session-001")).isEqualTo(expected);
    }

    // 功能：验证 Unicode thread 消息按完整 JSONL 行追加并以模型消息结构读回
    // 设计：用固定时钟追加配平的 tool_use/tool_result，再断言读取结果剥离 ts 与 run_id
    @Test
    void appendsAndReadsUnicodeThreadMessages(@TempDir Path temp) throws Exception {
        Clock clock = Clock.fixed(Instant.parse("2026-07-19T10:15:30Z"), ZoneOffset.UTC);
        SessionStore store = new SessionStore(temp.resolve("sessions"), clock);
        var mapper = ProtocolJson.mapper();

        store.appendMessage("session-001", "user", mapper.getNodeFactory().textNode("读取文档"), "run-001");
        store.appendMessage("session-001", "assistant", mapper.readTree("""
                [{"type":"tool_use","id":"tool-001","name":"read_file","input":{"path":"文档.txt"}}]
                """), "run-001");
        store.appendMessage("session-001", "user", mapper.readTree("""
                [{"type":"tool_result","tool_use_id":"tool-001","content":"文件内容"}]
                """), "run-001");

        JsonNode actual = mapper.valueToTree(store.readMessages("session-001"));
        JsonNode expected = mapper.readTree("""
                [
                  {"role":"user","content":"读取文档"},
                  {"role":"assistant","content":[{"type":"tool_use","id":"tool-001","name":"read_file","input":{"path":"文档.txt"}}]},
                  {"role":"user","content":[{"type":"tool_result","tool_use_id":"tool-001","content":"文件内容"}]}
                ]
                """);

        assertThat(actual).isEqualTo(expected);
    }

    // 功能：验证 notes.md 缺失时读空且 Unicode 笔记可按固定时间追加
    // 设计：先通过公开接口读取冷启动状态，再追加一条笔记并比较完整 UTF-8 文本
    @Test
    void appendsAndReadsUnicodeNotes(@TempDir Path temp) throws Exception {
        Clock clock = Clock.fixed(Instant.parse("2026-07-19T10:15:30Z"), ZoneOffset.UTC);
        SessionStore store = new SessionStore(temp.resolve("sessions"), clock);

        assertThat(store.readNotes("session-001")).isEmpty();

        store.appendNote("session-001", "中文笔记", "run-001");

        assertThat(store.readNotes("session-001"))
                .isEqualTo("## Note (2026-07-19T10:15:30Z, run-001)\n中文笔记\n\n");
    }

    // 功能：验证超长字符串 tool_result 只在读取模型上下文时被截断
    // 设计：追加配平工具调用与 8001 字符结果，断言返回前 4000 字符及精确省略计数
    @Test
    void truncatesLargeToolResultsInMemory(@TempDir Path temp) throws Exception {
        SessionStore store = new SessionStore(temp.resolve("sessions"), Clock.systemUTC());
        var mapper = ProtocolJson.mapper();
        store.appendMessage("session-001", "assistant", mapper.readTree("""
                [{"type":"tool_use","id":"tool-001","name":"read_file","input":{}}]
                """), "run-001");
        ObjectNode result = mapper.createObjectNode();
        result.put("type", "tool_result");
        result.put("tool_use_id", "tool-001");
        result.put("content", "x".repeat(8_001));
        store.appendMessage(
                "session-001", "user", mapper.createArrayNode().add(result), "run-001");

        String content = store.readMessages("session-001")
                .get(1).path("content").get(0).path("content").asText();

        assertThat(content).isEqualTo(
                "x".repeat(4_000) + "\n[... 4001 chars omitted. Full output in run events.]");
    }

    // 功能：验证配置化 tool-result 截断按 Unicode code point 精确保留
    // 设计：limit=4/keep=2 写入五个 emoji，读取后观察两个完整 emoji 与省略三个字符
    @Test
    void truncatesToolResultsWithConfiguredUnicodeLimits(@TempDir Path temp) throws Exception {
        SessionStore store = new SessionStore(
                temp.resolve("sessions"), Clock.systemUTC(), 4, 2);
        var mapper = ProtocolJson.mapper();
        store.appendMessage("session-001", "assistant", mapper.readTree("""
                [{"type":"tool_use","id":"tool-001","name":"read_file","input":{}}]
                """), "run-001");
        ObjectNode result = mapper.createObjectNode();
        result.put("type", "tool_result");
        result.put("tool_use_id", "tool-001");
        result.put("content", "😀😁😂😃😄");
        store.appendMessage(
                "session-001", "user", mapper.createArrayNode().add(result), "run-001");

        String content = store.readMessages("session-001")
                .get(1).path("content").get(0).path("content").asText();

        assertThat(content).isEqualTo(
                "😀😁\n[... 3 chars omitted. Full output in run events.]");
    }

    // 功能：验证 compact 原子替换 thread 并保留确定命名的原始备份
    // 设计：固定时钟写旧消息后压缩成两条新消息，分别读取主文件与备份观察结果
    @Test
    void writesCompactedThreadAndBacksUpOriginal(@TempDir Path temp) throws Exception {
        Clock clock = Clock.fixed(Instant.parse("2026-07-19T10:15:30Z"), ZoneOffset.UTC);
        SessionStore store = new SessionStore(temp.resolve("sessions"), clock);
        var mapper = ProtocolJson.mapper();
        store.appendMessage("session-001", "user", mapper.getNodeFactory().textNode("original"), "run-001");
        ObjectNode summary = mapper.createObjectNode()
                .put("role", "user")
                .put("content", "Summary of the conversation");
        ObjectNode acknowledgement = mapper.createObjectNode()
                .put("role", "assistant")
                .put("content", "Understood");

        store.writeCompacted("session-001", List.of(summary, acknowledgement));

        JsonNode actual = mapper.valueToTree(store.readMessages("session-001"));
        assertThat(actual).isEqualTo(mapper.valueToTree(List.of(summary, acknowledgement)));
        Path backup = store.sessionDirectory("session-001")
                .resolve("thread_20260719_101530.jsonl.bak");
        assertThat(backup).content(StandardCharsets.UTF_8).contains("original");
    }

    // 功能：验证 compact 备份失败时原 thread 保持不变且临时文件被清理
    // 设计：占用固定时钟对应的备份路径制造复制失败，再比较调用前后主文件字节
    @Test
    void compactFailurePreservesOriginalThread(@TempDir Path temp) throws Exception {
        Clock clock = Clock.fixed(Instant.parse("2026-07-19T10:15:30Z"), ZoneOffset.UTC);
        SessionStore store = new SessionStore(temp.resolve("sessions"), clock);
        var mapper = ProtocolJson.mapper();
        store.appendMessage("session-001", "user", mapper.getNodeFactory().textNode("original"), "run-001");
        Path directory = store.sessionDirectory("session-001");
        Path thread = directory.resolve("thread.jsonl");
        String original = Files.readString(thread, StandardCharsets.UTF_8);
        Files.createDirectory(directory.resolve("thread_20260719_101530.jsonl.bak"));
        ObjectNode replacement = mapper.createObjectNode()
                .put("role", "user")
                .put("content", "replacement");

        assertThatThrownBy(() -> store.writeCompacted("session-001", List.of(replacement)))
                .isInstanceOf(java.io.IOException.class);

        assertThat(Files.readString(thread, StandardCharsets.UTF_8)).isEqualTo(original);
        assertThat(directory.resolve("thread.jsonl.tmp")).doesNotExist();
    }

    // 功能：列出有效 session 并按更新时间倒序排列
    // 设计：混合两份 metadata 和一个损坏目录，验证容错发现与稳定排序
    @Test
    void listsValidMetadataByRecentUpdate(@TempDir Path temp) throws Exception {
        SessionStore store = new SessionStore(temp.resolve("sessions"), Clock.systemUTC());
        store.writeMeta(new Session(
                "older", io.klaude.protocol.SessionMode.CHAT,
                io.klaude.protocol.SessionStatus.ACTIVE, "old",
                "2026-07-19T00:00:00Z", "2026-07-19T00:00:00Z", List.of()));
        store.writeMeta(new Session(
                "newer", io.klaude.protocol.SessionMode.CHAT,
                io.klaude.protocol.SessionStatus.WAITING_FOR_INPUT, "new",
                "2026-07-20T00:00:00Z", "2026-07-20T00:00:00Z", List.of("run-1")));
        Files.createDirectories(store.sessionDirectory("broken"));
        Files.writeString(store.sessionDirectory("broken").resolve("meta.json"), "not-json");

        assertThat(store.listMeta()).extracting(Session::id).containsExactly("newer", "older");
    }

    // 将测试 classpath 中的 fixture 目录转换为文件系统路径
    private static Path fixturePath(String resource) throws Exception {
        URI uri = Objects.requireNonNull(SessionStoreTest.class.getResource(resource)).toURI();
        return Path.of(uri);
    }
}
