package io.klaude.llm;

import java.time.Duration;
import java.util.concurrent.CompletionStage;

@FunctionalInterface
public interface RetryDelay {
    // 异步等待一个可注入的 retry 时长
    CompletionStage<Void> waitFor(Duration duration);
}
