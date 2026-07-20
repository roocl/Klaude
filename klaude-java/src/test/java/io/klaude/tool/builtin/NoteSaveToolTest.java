package io.klaude.tool.builtin;

import static org.assertj.core.api.Assertions.assertThat;

import io.klaude.protocol.ProtocolJson;
import io.klaude.tool.ToolContext;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class NoteSaveToolTest {
    // 功能：验证 note_save 将当前 session/run 和去除边空白的内容传给注入服务
    // 设计：用 fake service 捕获三个参数，执行 Unicode 笔记后观察 saved 结果
    @Test
    void savesTrimmedNoteForCurrentSessionAndRun(@TempDir Path temp) throws Exception {
        AtomicReference<String> sessionId = new AtomicReference<>();
        AtomicReference<String> content = new AtomicReference<>();
        AtomicReference<String> runId = new AtomicReference<>();
        NoteService service = (requestedSessionId, requestedContent, requestedRunId) -> {
            sessionId.set(requestedSessionId);
            content.set(requestedContent);
            runId.set(requestedRunId);
        };

        var result = new NoteSaveTool(service).execute(
                        new ToolContext(temp, "session-001", "run-009", "tool-001"),
                        ProtocolJson.mapper().createObjectNode().put("content", "  保留这项决定  "))
                .toCompletableFuture().get();

        assertThat(result.isError()).isFalse();
        assertThat(result.content()).isEqualTo("saved");
        assertThat(sessionId).hasValue("session-001");
        assertThat(content).hasValue("保留这项决定");
        assertThat(runId).hasValue("run-009");
    }

    // 功能：验证 note_save 拒绝全空白内容且不调用持久化服务
    // 设计：用计数 fake service 执行仅含空白的参数，观察 runtime_error 和零次调用
    @Test
    void rejectsBlankNoteWithoutCallingService(@TempDir Path temp) throws Exception {
        AtomicInteger calls = new AtomicInteger();
        NoteService service = (sessionId, content, runId) -> calls.incrementAndGet();

        var result = new NoteSaveTool(service).execute(
                        new ToolContext(temp, "session-001", "run-001", "tool-001"),
                        ProtocolJson.mapper().createObjectNode().put("content", " \t\r\n "))
                .toCompletableFuture().get();

        assertThat(result.isError()).isTrue();
        assertThat(result.content()).isEqualTo("empty content");
        assertThat(calls).hasValue(0);
    }
}
