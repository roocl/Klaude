package io.klaude.agent;

import java.util.concurrent.CompletionStage;

@FunctionalInterface
public interface AgentCompactor {
    // 压缩当前 execution context 并在完成后保留合法模型消息
    CompletionStage<Void> compact(ExecutionContext context);
}
