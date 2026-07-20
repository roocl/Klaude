package io.klaude.tool.builtin;

import static org.assertj.core.api.Assertions.assertThat;

import io.klaude.protocol.ProtocolJson;
import io.klaude.tool.ToolContext;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class TaskCreateToolTest {
    // 功能：验证 task_create 传递任务字段并返回注入服务创建的 JSON
    // 设计：用 fake service 捕获 Unicode subject、默认 description 和 blocked_by
    @Test
    void createsTaskThroughInjectedService(@TempDir Path temp) throws Exception {
        AtomicReference<String> subject = new AtomicReference<>();
        AtomicReference<String> description = new AtomicReference<>();
        AtomicReference<List<Integer>> blockedBy = new AtomicReference<>();
        TaskCreateService service = (requestedSubject, requestedDescription, requestedBlockedBy) -> {
            subject.set(requestedSubject);
            description.set(requestedDescription);
            blockedBy.set(requestedBlockedBy);
            return ProtocolJson.mapper().createObjectNode()
                    .put("id", 3)
                    .put("subject", requestedSubject);
        };

        var result = new TaskCreateTool(service).execute(
                        new ToolContext(temp, "session-001", "run-001", "tool-001"),
                        ProtocolJson.mapper().createObjectNode()
                                .put("subject", "迁移工具")
                                .set("blocked_by", ProtocolJson.mapper().createArrayNode().add(1).add(2)))
                .toCompletableFuture().get();

        assertThat(result.isError()).isFalse();
        assertThat(ProtocolJson.mapper().readTree(result.content()))
                .isEqualTo(ProtocolJson.mapper().createObjectNode()
                        .put("id", 3)
                        .put("subject", "迁移工具"));
        assertThat(subject).hasValue("迁移工具");
        assertThat(description).hasValue("");
        assertThat(blockedBy).hasValue(List.of(1, 2));
    }
}
