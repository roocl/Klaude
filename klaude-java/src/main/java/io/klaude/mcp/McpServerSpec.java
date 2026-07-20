package io.klaude.mcp;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public record McpServerSpec(
        String name,
        String transport,
        List<String> command,
        Map<String, String> environment,
        String host,
        int port) {
    // 校验 server spec 并创建集合不可变副本
    public McpServerSpec {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(transport, "transport");
        command = List.copyOf(Objects.requireNonNull(command, "command"));
        environment = Map.copyOf(Objects.requireNonNull(environment, "environment"));
        Objects.requireNonNull(host, "host");
    }

    // 创建一个 stdio server spec
    public static McpServerSpec stdio(
            String name, List<String> command, Map<String, String> environment) {
        return new McpServerSpec(name, "stdio", command, environment, "", 0);
    }

    // 创建一个 TCP server spec
    public static McpServerSpec tcp(String name, String host, int port) {
        return new McpServerSpec(name, "tcp", List.of(), Map.of(), host, port);
    }
}
