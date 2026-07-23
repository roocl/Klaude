package io.klaude.cli;

import io.github.cdimascio.dotenv.Dotenv;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.tomlj.Toml;

record CliEndpoint(String host, int port, Path traceFile) {
    // 按 daemon 相同优先级读取 CLI 所需的连接地址
    static CliEndpoint load(Path workingDirectory, Path homeDirectory, Map<String, String> environment) {
        Path cwd = workingDirectory.toAbsolutePath().normalize();
        Path home = homeDirectory.toAbsolutePath().normalize();
        Mutable endpoint = new Mutable(
                "127.0.0.1", 7437, home.resolve(".klaude/traces/daemon.jsonl"));
        Map<String, String> dotenv = loadDotenv(cwd);
        String explicit = environment.getOrDefault("KLAUDE_CONFIG", dotenv.get("KLAUDE_CONFIG"));
        if (explicit == null) {
            applyToml(endpoint, home.resolve(".klaude/config.toml"), cwd, home);
            applyToml(endpoint, cwd.resolve(".klaude/config.toml"), cwd, home);
        } else {
            applyToml(endpoint, resolvePath(explicit, cwd, home), cwd, home);
        }
        applyEnvironment(endpoint, dotenv);
        applyEnvironment(endpoint, environment);
        return endpoint.freeze();
    }

    // 读取项目 .env 中显式声明的连接覆盖
    private static Map<String, String> loadDotenv(Path cwd) {
        Dotenv dotenv = Dotenv.configure()
                .directory(cwd.toString())
                .ignoreIfMissing()
                .load();
        Map<String, String> values = new HashMap<>();
        for (var entry : dotenv.entries(Dotenv.Filter.DECLARED_IN_ENV_FILE)) {
            values.put(entry.getKey(), entry.getValue());
        }
        return Map.copyOf(values);
    }

    // 解析显式配置路径的 home 与工作目录语义
    private static Path resolvePath(String value, Path cwd, Path home) {
        if (value.equals("~")) {
            return home;
        }
        if (value.startsWith("~/") || value.startsWith("~\\")) {
            return home.resolve(value.substring(2)).normalize();
        }
        Path path = Path.of(value);
        return path.isAbsolute() ? path.normalize() : cwd.resolve(path).normalize();
    }

    // 从一个存在的 TOML 文件读取 core host 与 port
    private static void applyToml(Mutable endpoint, Path path, Path cwd, Path home) {
        if (!Files.exists(path)) {
            return;
        }
        final org.tomlj.TomlParseResult toml;
        try {
            toml = Toml.parse(path);
        } catch (java.io.IOException error) {
            throw new IllegalArgumentException("cannot read config: " + path, error);
        }
        if (toml.hasErrors()) {
            throw new IllegalArgumentException("cannot parse config: " + path + ": " + toml.errors());
        }
        String host = toml.getString("core.host");
        Long port = toml.getLong("core.port");
        if (host != null) {
            endpoint.host = host;
        }
        if (port != null) {
            endpoint.port = Math.toIntExact(port);
        }
        if (toml.contains("trace.file")) {
            String trace = toml.getString("trace.file");
            endpoint.traceFile = resolvePath(trace, cwd, home);
        }
    }

    // 应用 .env 或进程环境中的连接覆盖
    private static void applyEnvironment(Mutable endpoint, Map<String, String> environment) {
        if (environment.containsKey("KLAUDE_HOST")) {
            endpoint.host = environment.get("KLAUDE_HOST");
        }
        if (environment.containsKey("KLAUDE_PORT")) {
            try {
                endpoint.port = Integer.parseInt(environment.get("KLAUDE_PORT"));
            } catch (NumberFormatException error) {
                throw new IllegalArgumentException("KLAUDE_PORT must be an integer", error);
            }
        }
        if (environment.containsKey("KLAUDE_TRACE_FILE")) {
            endpoint.traceFile = Path.of(environment.get("KLAUDE_TRACE_FILE"))
                    .toAbsolutePath().normalize();
        }
    }

    private static final class Mutable {
        private String host;
        private int port;
        private Path traceFile;

        // 保存合并过程中的可变 endpoint
        private Mutable(String host, int port, Path traceFile) {
            this.host = host;
            this.port = port;
            this.traceFile = traceFile;
        }

        // 校验并冻结最终连接地址
        private CliEndpoint freeze() {
            if (host == null || host.isBlank()) {
                throw new IllegalArgumentException("daemon host must not be blank");
            }
            if (port < 1 || port > 65_535) {
                throw new IllegalArgumentException("daemon port must be between 1 and 65535");
            }
            return new CliEndpoint(host, port, traceFile.toAbsolutePath().normalize());
        }
    }
}
