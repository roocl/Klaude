package io.klaude.agent;

import static org.assertj.core.api.Assertions.assertThat;

import io.klaude.protocol.ProtocolJson;
import java.util.List;
import org.junit.jupiter.api.Test;

final class ExecutionContextTest {
    // 功能：验证新 execution context 从 step 0/running 开始并将 goal 包装为 user 消息
    // 设计：使用 Unicode goal 构造公开 context，观察状态、步数和第一条 provider message
    @Test
    void startsRunningWithGoalAsUserMessage() {
        var context = new ExecutionContext("run-001", "完成迁移", 5);

        assertThat(context.isDone()).isFalse();
        assertThat(context.step()).isZero();
        assertThat(context.messages()).singleElement().satisfies(message -> {
            assertThat(message.path("role").asText()).isEqualTo("user");
            assertThat(message.path("content").asText()).isEqualTo("完成迁移");
        });
    }

    // 功能：验证非空预填历史替代 goal 消息并与调用方后续修改隔离
    // 设计：传入一条可变 Unicode 消息后修改原对象，观察 context 保留构造时的完整副本
    @Test
    void startsFromDefensivelyCopiedPrefillHistory() {
        var message = ProtocolJson.mapper().createObjectNode()
                .put("role", "user")
                .put("content", "上一轮问题");
        var context = new ExecutionContext("run-001", "新目标", 5, List.of(message));

        message.put("content", "已被外部修改");

        assertThat(context.messages()).singleElement().satisfies(saved -> {
            assertThat(saved.path("role").asText()).isEqualTo("user");
            assertThat(saved.path("content").asText()).isEqualTo("上一轮问题");
        });
    }

    // 功能：验证 system prompt 按 Global、Project、Session Notes 顺序组装并保留 note_save 提示
    // 设计：通过完整 context 构造入口注入三层文本和 override，观察公开 prompt 的内容与顺序
    @Test
    void assemblesLayeredSystemPromptInReferenceOrder() {
        var context = new ExecutionContext(
                "run-001",
                "goal",
                5,
                List.of(),
                "session note",
                "global line",
                "project line",
                "OVERRIDE");

        String prompt = context.systemPrompt("BASE");

        assertThat(prompt).startsWith("OVERRIDE");
        assertThat(prompt).contains("## Global Context\nglobal line");
        assertThat(prompt).contains("## Project Context\nproject line");
        assertThat(prompt).contains("## Session Notes\nsession note");
        assertThat(prompt).contains("note_save");
        assertThat(prompt.indexOf("Global"))
                .isLessThan(prompt.indexOf("Project"));
        assertThat(prompt.indexOf("Project"))
                .isLessThan(prompt.indexOf("Session"));
    }
}
