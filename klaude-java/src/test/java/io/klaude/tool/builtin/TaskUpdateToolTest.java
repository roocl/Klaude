package io.klaude.tool.builtin;

import static org.assertj.core.api.Assertions.assertThat;

import io.klaude.protocol.ProtocolJson;
import io.klaude.tool.ToolContext;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class TaskUpdateToolTest {
    // 功能：验证 task_update 传递状态与依赖变更并返回更新后的 JSON
    // 设计：用 fake service 捕获不可变 update request，返回 completed 任务后观察结果
    @Test
    void updatesTaskThroughInjectedService(@TempDir Path temp) throws Exception {
        AtomicReference<TaskUpdateRequest> request = new AtomicReference<>();
        TaskUpdateService service = requestedUpdate -> {
            request.set(requestedUpdate);
            return ProtocolJson.mapper().createObjectNode()
                    .put("id", requestedUpdate.taskId())
                    .put("status", requestedUpdate.status());
        };
        var params = ProtocolJson.mapper().createObjectNode()
                .put("task_id", 4)
                .put("status", "completed");
        params.set("add_blocked_by", ProtocolJson.mapper().createArrayNode().add(1).add(2));
        params.set("remove_blocked_by", ProtocolJson.mapper().createArrayNode().add(3));

        var result = new TaskUpdateTool(service).execute(
                        new ToolContext(temp, "session-001", "run-001", "tool-001"),
                        params)
                .toCompletableFuture().get();

        assertThat(result.isError()).isFalse();
        assertThat(request).hasValue(new TaskUpdateRequest(
                4, "completed", List.of(1, 2), List.of(3)));
        assertThat(ProtocolJson.mapper().readTree(result.content()))
                .isEqualTo(ProtocolJson.mapper().createObjectNode()
                        .put("id", 4)
                        .put("status", "completed"));
    }
}
