package io.klaude.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.klaude.protocol.LlmTokenEvent;
import io.klaude.protocol.RunFinishedEvent;
import io.klaude.protocol.ToolCallFinishedEvent;
import io.klaude.protocol.PermissionRequestedEvent;
import io.klaude.protocol.PermissionGrantedEvent;
import io.klaude.protocol.ProtocolJson;
import org.junit.jupiter.api.Test;

final class TuiModelTest {
    // 功能：将流式 token 在 run 完成时收束为对话记录
    // 设计：依次投影两个 token 和完成事件并检查不可变快照
    @Test
    void collectsStreamingAnswer() {
        var model = new TuiModel();

        model.accept(new LlmTokenEvent("run-1", "你", "2026-01-01T00:00:00Z"));
        model.accept(new LlmTokenEvent("run-1", "好", "2026-01-01T00:00:01Z"));
        model.accept(new RunFinishedEvent(
                "run-1", "success", "end_turn", 1, "2026-01-01T00:00:02Z"));

        assertThat(model.snapshot().transcript())
                .containsExactly("AI: 你好", "[run] success (1 steps)");
        assertThat(model.snapshot().status()).isEqualTo("success");
        assertThat(model.busy()).isFalse();
    }

    // 功能：跟踪一轮消息的忙碌状态与连接中断状态
    // 设计：通过公开状态转换验证重复输入和断线保护所需信号
    @Test
    void tracksBusyAndDisconnectedStates() {
        var model = new TuiModel();

        model.userMessage("hello");
        assertThat(model.busy()).isTrue();

        model.disconnected("socket closed");
        assertThat(model.busy()).isTrue();
        assertThat(model.snapshot().connected()).isFalse();
        assertThat(model.snapshot().status()).isEqualTo("disconnected");
    }

    // 功能：默认隐藏工具详情并允许用户展开查看
    // 设计：投影包含输出的工具事件并比较切换前后的快照
    @Test
    void togglesToolDetails() {
        var model = new TuiModel();
        model.accept(new ToolCallFinishedEvent(
                "run-1", "tool-1", "read", 2, "secret output", "2026-01-01T00:00:00Z"));

        assertThat(model.snapshot().transcript()).doesNotContain("  output: secret output");
        model.toggleToolDetails();
        assertThat(model.snapshot().transcript()).contains("  output: secret output");
    }

    // 功能：重放重复事件时不重复显示并清理已解决权限
    // 设计：两次投影相同请求后再投影 granted，模拟断线回放完整事件序列
    @Test
    void deduplicatesReplayAndResolvesPermission() {
        var model = new TuiModel();
        var params = ProtocolJson.mapper().createObjectNode().put("path", "README.md");
        var requested = new PermissionRequestedEvent(
                "run-1", "tool-1", "read_file", params, "path=README.md", "session-1",
                "2026-01-01T00:00:00Z");

        model.accept(requested);
        model.accept(requested);
        model.accept(new PermissionGrantedEvent(
                "run-1", "tool-1", "allow_once", "2026-01-01T00:00:01Z"));

        assertThat(model.snapshot().permission()).isNull();
    }

    // 功能：在界面历史超过上限时只保留最新记录
    // 设计：投影大量工具完成事件并验证固定窗口不会无限增长
    @Test
    void boundsTranscriptHistory() {
        var model = new TuiModel();

        for (int index = 0; index < 250; index++) {
            model.accept(new ToolCallFinishedEvent(
                    "run-1", "tool-" + index, "read", 1, "", "2026-01-01T00:00:00Z"));
        }

        assertThat(model.snapshot().transcript()).hasSize(200);
    }
}
