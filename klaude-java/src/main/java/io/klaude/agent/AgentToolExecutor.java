package io.klaude.agent;

import io.klaude.llm.LlmToolCall;
import io.klaude.tool.ToolResult;
import java.util.concurrent.CompletionStage;

@FunctionalInterface
public interface AgentToolExecutor {
    // 串行执行一个 LLM 工具调用并返回可观察结果
    CompletionStage<ToolResult> invoke(LlmToolCall call);
}
