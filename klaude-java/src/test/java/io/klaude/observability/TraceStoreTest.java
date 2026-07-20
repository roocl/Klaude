package io.klaude.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Objects;
import io.klaude.protocol.ProtocolJson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class TraceStoreTest {
    // 功能：验证冻结 trace fixture 的 Unicode、嵌套 data 与 null 字段完整读取
    // 设计：通过公开 store 读取冻结 JSONL，并直接观察第一条 JSON tree 的关键契约字段
    @Test
    void readsPhaseZeroTraceFixture() throws Exception {
        TraceStore store = new TraceStore(fixturePath("/fixtures/trace/trace.jsonl"));

        var records = store.readAll();

        assertThat(records).hasSize(1);
        assertThat(records.getFirst().path("direction").asText()).isEqualTo("CLIENT→CORE");
        assertThat(records.getFirst().path("data").path("params").path("client").asText())
                .isEqualTo("客户端");
        assertThat(records.getFirst().get("run_id").isNull()).isTrue();
    }

    // 功能：验证 Unicode trace object 按调用顺序追加并自动创建父目录
    // 设计：向临时深层路径追加两条 tree，再仅通过公开 readAll 比较完整列表
    @Test
    void appendsUnicodeTraceRecordsInOrder(@TempDir Path temp) throws Exception {
        TraceStore store = new TraceStore(temp.resolve("deep/traces/trace.jsonl"));
        var mapper = ProtocolJson.mapper();
        var first = mapper.createObjectNode().put("direction", "CLIENT→CORE").put("message", "你好");
        var second = mapper.createObjectNode().put("direction", "CORE→CLIENT").put("message", "完成");

        store.append(first);
        store.append(second);

        assertThat(store.readAll()).isEqualTo(List.of(first, second));
    }

    // 功能：验证损坏与非 object JSONL 行不会阻断合法 trace 历史读取
    // 设计：在两次公开 append 之间直接注入两类坏行，断言 readAll 仅返回合法记录且顺序不变
    @Test
    void skipsBrokenAndNonObjectTraceRows(@TempDir Path temp) throws Exception {
        Path path = temp.resolve("trace.jsonl");
        TraceStore store = new TraceStore(path);
        var mapper = ProtocolJson.mapper();
        var first = mapper.createObjectNode().put("message", "before");
        var second = mapper.createObjectNode().put("message", "after");
        store.append(first);
        Files.writeString(
                path,
                "{broken-json\n[1,2,3]\n",
                StandardCharsets.UTF_8,
                StandardOpenOption.APPEND);
        store.append(second);

        assertThat(store.readAll()).isEqualTo(List.of(first, second));
    }

    // 将测试 classpath 中的 fixture 转换为文件系统路径
    private static Path fixturePath(String resource) throws Exception {
        URI uri = Objects.requireNonNull(TraceStoreTest.class.getResource(resource)).toURI();
        return Path.of(uri);
    }
}
