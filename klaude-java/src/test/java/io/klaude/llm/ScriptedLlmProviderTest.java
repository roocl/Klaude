package io.klaude.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.klaude.protocol.ProtocolJson;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

final class ScriptedLlmProviderTest {
    // 功能：验证 scripted provider 按声明顺序返回不可变 LLM 响应
    // 设计：注入 tool_use/end_turn 两个响应，连续 chat 后观察 Unicode tool call 与最终文本
    @Test
    void returnsScriptedResponsesInOrder() throws Exception {
        var toolCall = new LlmToolCall(
                "tool-001",
                "echo",
                ProtocolJson.mapper().createObjectNode().put("text", "你好"));
        var first = new LlmResponse(
                LlmStopReason.TOOL_USE,
                List.of(toolCall),
                "",
                null,
                List.of());
        var second = new LlmResponse(
                LlmStopReason.END_TURN,
                List.of(),
                "完成",
                null,
                List.of());
        var provider = new ScriptedLlmProvider(List.of(first, second));
        var request = new LlmRequest("run-001", 1, List.of(), List.of(), "system");
        LlmEventSink events = event -> CompletableFuture.completedFuture(null);

        assertThat(provider.chat(request, events).toCompletableFuture().get())
                .isEqualTo(first);
        assertThat(provider.chat(request, events).toCompletableFuture().get())
                .isEqualTo(second);
    }

    // 功能：验证 scripted provider 脚本耗尽时返回可诊断的异步失败
    // 设计：消费唯一响应后再次 chat，观察 future 以明确的耗尽异常结束
    @Test
    void failsClearlyWhenScriptIsExhausted() throws Exception {
        var response = new LlmResponse(
                LlmStopReason.END_TURN, List.of(), "done", null, List.of());
        var provider = new ScriptedLlmProvider(List.of(response));
        var request = new LlmRequest("run-001", 1, List.of(), List.of(), "system");
        LlmEventSink events = event -> CompletableFuture.completedFuture(null);

        assertThat(provider.chat(request, events).toCompletableFuture().get()).isEqualTo(response);
        assertThatThrownBy(() -> provider.chat(request, events).toCompletableFuture().join())
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .hasRootCauseMessage("scripted LLM response exhausted");
    }

    // 功能：验证 scripted provider 可在既定响应后注入确定性失败
    // 设计：配置一条成功响应和指定异常，连续 chat 后观察原异常被保留
    @Test
    void returnsScriptedFailureAfterResponses() throws Exception {
        var response = new LlmResponse(
                LlmStopReason.TOOL_USE, List.of(), "", null, List.of());
        var failure = new IllegalStateException("scripted failure");
        var provider = ScriptedLlmProvider.failingAfter(List.of(response), failure);
        var request = new LlmRequest("run-001", 1, List.of(), List.of(), "system");
        LlmEventSink events = event -> CompletableFuture.completedFuture(null);

        assertThat(provider.chat(request, events).toCompletableFuture().get()).isEqualTo(response);
        assertThatThrownBy(() -> provider.chat(request, events).toCompletableFuture().join())
                .hasRootCause(failure);
    }
}
