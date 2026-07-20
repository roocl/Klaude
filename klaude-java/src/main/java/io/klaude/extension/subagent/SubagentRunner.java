package io.klaude.extension.subagent;

import io.klaude.agent.RunOutcome;
import java.util.concurrent.CompletionStage;

@FunctionalInterface
public interface SubagentRunner {
    // 执行一个隔离 child run 并返回最终 outcome
    CompletionStage<RunOutcome> run(SubagentRunRequest request);
}
