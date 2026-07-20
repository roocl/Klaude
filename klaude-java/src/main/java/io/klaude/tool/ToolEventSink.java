package io.klaude.tool;

import io.klaude.protocol.Event;
import java.util.concurrent.CompletionStage;

@FunctionalInterface
public interface ToolEventSink {
    // 异步发布一个工具或权限协议事件
    CompletionStage<Void> emit(Event event);
}
