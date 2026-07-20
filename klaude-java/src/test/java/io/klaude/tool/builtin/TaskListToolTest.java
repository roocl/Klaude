package io.klaude.tool.builtin;

import static org.assertj.core.api.Assertions.assertThat;

import io.klaude.protocol.ProtocolJson;
import io.klaude.tool.ToolContext;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class TaskListToolTest {
    // 功能：验证 task_list 返回注入服务生成的任务摘要
    // 设计：用计数 fake service 返回 Unicode 文本，观察调用次数和结果内容
    @Test
    void listsTasksThroughInjectedService(@TempDir Path temp) throws Exception {
        AtomicInteger calls = new AtomicInteger();
        TaskListService service = () -> {
            calls.incrementAndGet();
            return "#1 [pending] 迁移协议\n#2 [completed] 写入测试";
        };

        var result = new TaskListTool(service).execute(
                        new ToolContext(temp, "session-001", "run-001", "tool-001"),
                        ProtocolJson.mapper().createObjectNode())
                .toCompletableFuture().get();

        assertThat(result.isError()).isFalse();
        assertThat(result.content())
                .isEqualTo("#1 [pending] 迁移协议\n#2 [completed] 写入测试");
        assertThat(calls).hasValue(1);
    }
}
