package io.klaude.daemon.config;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public record RuntimeConfig(
        String host,
        int port,
        Logging logging,
        Agent agent,
        Llm llm,
        Trace trace,
        Permission permission,
        Compaction compaction,
        Mcp mcp) {

    public record Logging(String level, Path file, String format) {
    }

    public record Agent(int maxSteps) {
    }

    public record Llm(String defaultModel, String router) {
    }

    public record Trace(boolean enabled, Path file, boolean includeLlmPayload) {
    }

    public record Permission(double timeoutSeconds) {
    }

    public record Compaction(double autoThreshold, int toolResultLimit, int toolResultKeep) {
    }

    public record Mcp(List<McpServer> servers) {
        // 创建不可变 MCP server 列表
        public Mcp {
            servers = List.copyOf(servers);
        }
    }

    public record McpServer(
            String name,
            String transport,
            String command,
            List<String> args,
            Map<String, String> environment,
            String host,
            int port) {

        // 创建 MCP server 参数的不可变副本
        public McpServer {
            args = List.copyOf(args);
            environment = Map.copyOf(environment);
        }
    }
}
