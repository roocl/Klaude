package io.klaude.protocol;

import java.util.List;
import java.util.Objects;

public record EventSubscribeCommand(
        List<String> topics,
        String scope,
        String replayFromRun) implements Command {

    // 校验订阅字段并填充 scope 默认值
    public EventSubscribeCommand {
        topics = List.copyOf(Objects.requireNonNull(topics, "topics"));
        scope = scope == null ? "global" : scope;
    }
}
