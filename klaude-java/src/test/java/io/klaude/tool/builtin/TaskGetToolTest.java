package io.klaude.tool.builtin;

import static org.assertj.core.api.Assertions.assertThat;

import io.klaude.protocol.ProtocolJson;
import io.klaude.tool.ToolContext;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class TaskGetToolTest {
    // 功能：验证 task_get 传递整数 ID 并返回注入服务查到的 JSON
    // 设计：用 fake service 捕获 task_id，返回 Unicode 任务后观察序列化结果
    @Test
    void getsTaskThroughInjectedService(@TempDir Path temp) throws Exception {
        AtomicInteger taskId = new AtomicInteger();
        TaskGetService service = requestedTaskId -> {
            taskId.set(requestedTaskId);
            return ProtocolJson.mapper().createObjectNode()
                    .put("id", requestedTaskId)
                    .put("subject", "迁移完成");
        };

        var result = new TaskGetTool(service).execute(
                        new ToolContext(temp, "session-001", "run-001", "tool-001"),
                        ProtocolJson.mapper().createObjectNode().put("task_id", 7))
                .toCompletableFuture().get();

        assertThat(result.isError()).isFalse();
        assertThat(taskId).hasValue(7);
        assertThat(ProtocolJson.mapper().readTree(result.content()))
                .isEqualTo(ProtocolJson.mapper().createObjectNode()
                        .put("id", 7)
                        .put("subject", "迁移完成"));
    }
}
