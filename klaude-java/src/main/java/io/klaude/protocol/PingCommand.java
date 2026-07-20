package io.klaude.protocol;

import java.util.Objects;

public record PingCommand(String client) implements Command {
    // 校验 ping 客户端标识
    public PingCommand {
        Objects.requireNonNull(client, "client");
    }
}
