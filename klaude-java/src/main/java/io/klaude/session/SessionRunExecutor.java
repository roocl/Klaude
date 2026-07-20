package io.klaude.session;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.concurrent.CompletionStage;

@FunctionalInterface
public interface SessionRunExecutor {
    // 执行一次 session turn 并返回需要追加持久化的模型消息
    CompletionStage<List<ObjectNode>> run(SessionRunRequest request);
}
