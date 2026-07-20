package io.klaude.llm;

import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public final class ScriptedLlmProvider implements LlmProvider {
    private final ArrayDeque<LlmResponse> responses;
    private final Throwable terminalFailure;

    // 保存确定性响应脚本的防御性副本
    public ScriptedLlmProvider(List<LlmResponse> responses) {
        this(responses, null);
    }

    // 保存响应脚本及耗尽后需要返回的确定性失败
    private ScriptedLlmProvider(List<LlmResponse> responses, Throwable terminalFailure) {
        this.responses = new ArrayDeque<>(List.copyOf(responses));
        this.terminalFailure = terminalFailure;
    }

    // 创建在既定响应耗尽后返回指定异常的 provider
    public static ScriptedLlmProvider failingAfter(
            List<LlmResponse> responses, Throwable failure) {
        return new ScriptedLlmProvider(
                responses, java.util.Objects.requireNonNull(failure, "failure"));
    }

    // 按声明顺序取出下一个脚本响应
    @Override
    public synchronized CompletionStage<LlmResponse> chat(
            LlmRequest request, LlmEventSink events) {
        if (!responses.isEmpty()) {
            return CompletableFuture.completedFuture(responses.removeFirst());
        }
        Throwable failure = terminalFailure != null
                ? terminalFailure
                : new IllegalStateException("scripted LLM response exhausted");
        return CompletableFuture.failedFuture(failure);
    }
}
