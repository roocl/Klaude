package io.klaude.daemon.config;

import io.github.cdimascio.dotenv.Dotenv;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.tomlj.Toml;
import org.tomlj.TomlArray;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlTable;

public final class ConfigLoader {
    // 按默认、用户 TOML、项目 TOML、dotenv、系统环境顺序加载配置
    public RuntimeConfig load(Path workingDirectory, Path homeDirectory, Map<String, String> environment) {
        Path cwd = workingDirectory.toAbsolutePath().normalize();
        Path home = homeDirectory.toAbsolutePath().normalize();
        MutableConfig config = MutableConfig.defaults(home);
        Map<String, String> resolvedEnvironment = resolveEnvironment(cwd, environment);
        String explicit = resolvedEnvironment.get("KLAUDE_CONFIG");
        if (explicit == null) {
            applyTomlIfPresent(config, home.resolve(".klaude/config.toml"), cwd, home);
            applyTomlIfPresent(config, cwd.resolve(".klaude/config.toml"), cwd, home);
        } else {
            applyTomlIfPresent(config, resolvePath(explicit, cwd, home), cwd, home);
        }
        applyEnvironment(config, resolvedEnvironment, cwd, home);
        return config.freeze();
    }

    // 合并项目 dotenv 与进程环境并让进程变量优先
    public Map<String, String> resolveEnvironment(
            Path workingDirectory, Map<String, String> processEnvironment) {
        Path cwd = workingDirectory.toAbsolutePath().normalize();
        Map<String, String> resolved = new HashMap<>(loadDotenv(cwd));
        resolved.putAll(Map.copyOf(processEnvironment));
        return Map.copyOf(resolved);
    }

    // 展开 home 前缀并将相对路径解析到 daemon 工作目录
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

    // 只读取 .env 文件中显式声明的键值
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

    // 存在时解析并应用一个 TOML 文件
    private static void applyTomlIfPresent(MutableConfig config, Path path, Path cwd, Path home) {
        if (!Files.exists(path)) {
            return;
        }
        TomlParseResult toml;
        try {
            toml = Toml.parse(path);
        } catch (Exception error) {
            throw new ConfigException("cannot read config: " + path, error);
        }
        if (toml.hasErrors()) {
            throw new ConfigException("cannot parse config: " + path + ": " + toml.errors());
        }
        try {
            applyToml(config, toml, cwd, home);
        } catch (ConfigException error) {
            throw error;
        } catch (RuntimeException error) {
            throw new ConfigException("invalid config: " + path + ": " + error.getMessage(), error);
        }
    }

    // 校验 TOML 结构和值后将其应用到可变配置
    private static void applyToml(MutableConfig config, TomlParseResult toml, Path cwd, Path home) {
        requireAllowedKeys(toml, "root", Set.of(
                "core", "logging", "agent", "llm", "trace", "permission", "compaction", "mcp"));
        requireAllowedSectionKeys(toml, "core", Set.of("host", "port"));
        requireAllowedSectionKeys(toml, "logging", Set.of("level", "file", "format"));
        requireAllowedSectionKeys(toml, "agent", Set.of("max_steps"));
        requireAllowedSectionKeys(toml, "llm", Set.of("default_model", "router"));
        requireAllowedSectionKeys(toml, "trace", Set.of("enabled", "file", "include_llm_payload"));
        requireAllowedSectionKeys(toml, "permission", Set.of("timeout_s"));
        requireAllowedSectionKeys(toml, "compaction", Set.of(
                "auto_threshold", "tool_result_limit", "tool_result_keep"));
        requireAllowedSectionKeys(toml, "mcp", Set.of("servers"));
        String host = toml.getString("core.host");
        Long port = toml.getLong("core.port");
        if (host != null) {
            config.host = host;
        }
        if (port != null) {
            config.port = Math.toIntExact(port);
        }
        if (toml.contains("logging.level")) {
            config.logLevel = toml.getString("logging.level");
        }
        if (toml.contains("logging.file")) {
            config.logFile = resolvePath(toml.getString("logging.file"), cwd, home);
        }
        if (toml.contains("logging.format")) {
            config.logFormat = toml.getString("logging.format");
        }
        if (toml.contains("agent.max_steps")) {
            config.maxSteps = Math.toIntExact(toml.getLong("agent.max_steps"));
            requirePositive(config.maxSteps, "agent.max_steps");
        }
        if (toml.contains("llm.default_model")) {
            config.defaultModel = toml.getString("llm.default_model");
        }
        if (toml.contains("llm.router")) {
            config.router = toml.getString("llm.router");
        }
        if (toml.contains("trace.enabled")) {
            config.traceEnabled = toml.getBoolean("trace.enabled");
        }
        if (toml.contains("trace.file")) {
            config.traceFile = resolvePath(toml.getString("trace.file"), cwd, home);
        }
        if (toml.contains("trace.include_llm_payload")) {
            config.includeLlmPayload = toml.getBoolean("trace.include_llm_payload");
        }
        if (toml.contains("permission.timeout_s")) {
            config.permissionTimeoutSeconds = number(toml, "permission.timeout_s");
            if (config.permissionTimeoutSeconds < 0) {
                throw new ConfigException("permission.timeout_s must be non-negative");
            }
        }
        if (toml.contains("compaction.auto_threshold")) {
            config.autoThreshold = number(toml, "compaction.auto_threshold");
            if (config.autoThreshold < 0 || config.autoThreshold > 1) {
                throw new ConfigException("compaction.auto_threshold must be between 0 and 1");
            }
        }
        if (toml.contains("compaction.tool_result_limit")) {
            config.toolResultLimit = Math.toIntExact(toml.getLong("compaction.tool_result_limit"));
            requirePositive(config.toolResultLimit, "compaction.tool_result_limit");
        }
        if (toml.contains("compaction.tool_result_keep")) {
            config.toolResultKeep = Math.toIntExact(toml.getLong("compaction.tool_result_keep"));
            requirePositive(config.toolResultKeep, "compaction.tool_result_keep");
        }
        if (toml.contains("mcp.servers")) {
            config.mcpServers = parseMcpServers(toml.getArray("mcp.servers"));
        }
    }

    // 拒绝表中未声明的配置键
    private static void requireAllowedKeys(TomlTable table, String section, Set<String> allowed) {
        for (String key : table.keySet()) {
            if (!allowed.contains(key)) {
                throw new ConfigException("unknown config key: " + section + "." + key);
            }
        }
    }

    // 存在指定节时拒绝其中未声明的配置键
    private static void requireAllowedSectionKeys(
            TomlTable root, String section, Set<String> allowed) {
        TomlTable table = root.getTable(section);
        if (table != null) {
            requireAllowedKeys(table, section, allowed);
        }
    }

    // 拒绝非正整数配置值
    private static void requirePositive(int value, String key) {
        if (value <= 0) {
            throw new ConfigException(key + " must be a positive integer");
        }
    }

    // 读取 TOML 整数或浮点数并统一转换为 double
    private static double number(TomlTable table, String key) {
        Object value = table.get(key);
        if (value instanceof Long integer) {
            return integer.doubleValue();
        }
        if (value instanceof Double decimal) {
            return decimal;
        }
        throw new ConfigException(key + " must be a number");
    }

    // 解析 MCP array-of-tables 为不可变 server 配置
    private static List<RuntimeConfig.McpServer> parseMcpServers(TomlArray servers) {
        List<RuntimeConfig.McpServer> result = new ArrayList<>();
        for (int index = 0; index < servers.size(); index++) {
            TomlTable server = servers.getTable(index);
            requireAllowedKeys(server, "mcp.servers[" + index + "]", Set.of(
                    "name", "transport", "command", "args", "env", "host", "port"));
            String name = server.getString("name");
            if (name == null || name.isBlank()) {
                throw new ConfigException("mcp.servers[" + index + "].name must not be empty");
            }
            String transport = server.getString("transport", () -> "stdio");
            if (!Set.of("stdio", "tcp").contains(transport)) {
                throw new ConfigException(
                        "mcp.servers[" + index + "].transport must be stdio or tcp");
            }
            List<String> args = new ArrayList<>();
            TomlArray argsArray = server.getArray("args");
            if (argsArray != null) {
                for (int argIndex = 0; argIndex < argsArray.size(); argIndex++) {
                    args.add(argsArray.getString(argIndex));
                }
            }
            Map<String, String> serverEnvironment = new HashMap<>();
            TomlTable environment = server.getTable("env");
            if (environment != null) {
                for (String key : environment.keySet()) {
                    serverEnvironment.put(key, environment.getString(key));
                }
            }
            int port = Math.toIntExact(server.getLong("port", () -> 3000L));
            requirePositive(port, "mcp.servers[" + index + "].port");
            result.add(new RuntimeConfig.McpServer(
                    name,
                    transport,
                    server.getString("command", () -> ""),
                    args,
                    serverEnvironment,
                    server.getString("host", () -> "localhost"),
                    port));
        }
        return List.copyOf(result);
    }

    // 应用受支持的环境变量覆盖
    private static void applyEnvironment(
            MutableConfig config,
            Map<String, String> environment,
            Path cwd,
            Path home) {
        if (environment.containsKey("KLAUDE_HOST")) {
            config.host = environment.get("KLAUDE_HOST");
        }
        if (environment.containsKey("KLAUDE_PORT")) {
            config.port = integer(environment, "KLAUDE_PORT");
        }
        if (environment.containsKey("KLAUDE_LOG_LEVEL")) {
            config.logLevel = environment.get("KLAUDE_LOG_LEVEL");
        }
        if (environment.containsKey("KLAUDE_LOG_FILE")) {
            config.logFile = resolvePath(environment.get("KLAUDE_LOG_FILE"), cwd, home);
        }
        if (environment.containsKey("KLAUDE_LOG_FORMAT")) {
            config.logFormat = environment.get("KLAUDE_LOG_FORMAT");
        }
        if (environment.containsKey("KLAUDE_MAX_STEPS")) {
            config.maxSteps = positiveInteger(environment, "KLAUDE_MAX_STEPS");
        }
        if (environment.containsKey("KLAUDE_LLM_DEFAULT_MODEL")) {
            config.defaultModel = environment.get("KLAUDE_LLM_DEFAULT_MODEL");
        }
        if (environment.containsKey("KLAUDE_TRACE_ENABLED")) {
            config.traceEnabled = booleanValue(environment.get("KLAUDE_TRACE_ENABLED"));
        }
        if (environment.containsKey("KLAUDE_TRACE_FILE")) {
            config.traceFile = resolvePath(environment.get("KLAUDE_TRACE_FILE"), cwd, home);
        }
        if (environment.containsKey("KLAUDE_TRACE_INCLUDE_LLM_PAYLOAD")) {
            config.includeLlmPayload = booleanValue(
                    environment.get("KLAUDE_TRACE_INCLUDE_LLM_PAYLOAD"));
        }
        if (environment.containsKey("KLAUDE_PERMISSION_TIMEOUT_S")) {
            config.permissionTimeoutSeconds = decimal(environment, "KLAUDE_PERMISSION_TIMEOUT_S");
            if (config.permissionTimeoutSeconds < 0) {
                throw new ConfigException("KLAUDE_PERMISSION_TIMEOUT_S must be non-negative");
            }
        }
        if (environment.containsKey("KLAUDE_COMPACT_THRESHOLD")) {
            config.autoThreshold = decimal(environment, "KLAUDE_COMPACT_THRESHOLD");
            if (config.autoThreshold < 0 || config.autoThreshold > 1) {
                throw new ConfigException("KLAUDE_COMPACT_THRESHOLD must be between 0 and 1");
            }
        }
        if (environment.containsKey("KLAUDE_COMPACT_TOOL_LIMIT")) {
            config.toolResultLimit = positiveInteger(environment, "KLAUDE_COMPACT_TOOL_LIMIT");
        }
        if (environment.containsKey("KLAUDE_COMPACT_TOOL_KEEP")) {
            config.toolResultKeep = positiveInteger(environment, "KLAUDE_COMPACT_TOOL_KEEP");
        }
    }

    // 解析环境整数并保留字段名上下文
    private static int integer(Map<String, String> environment, String key) {
        try {
            return Integer.parseInt(environment.get(key));
        } catch (NumberFormatException error) {
            throw new ConfigException(key + " must be an integer", error);
        }
    }

    // 解析严格正整数环境值
    private static int positiveInteger(Map<String, String> environment, String key) {
        int value = integer(environment, key);
        if (value <= 0) {
            throw new ConfigException(key + " must be a positive integer");
        }
        return value;
    }

    // 解析环境浮点数并保留字段名上下文
    private static double decimal(Map<String, String> environment, String key) {
        try {
            return Double.parseDouble(environment.get(key));
        } catch (NumberFormatException error) {
            throw new ConfigException(key + " must be a number", error);
        }
    }

    // 按参考配置规则解析布尔环境值
    private static boolean booleanValue(String value) {
        return !Set.of("0", "false", "no").contains(value.toLowerCase(java.util.Locale.ROOT));
    }

    private static final class MutableConfig {
        private String host;
        private int port;
        private Path home;
        private String logLevel;
        private Path logFile;
        private String logFormat;
        private int maxSteps;
        private String defaultModel;
        private String router;
        private boolean traceEnabled;
        private Path traceFile;
        private boolean includeLlmPayload;
        private double permissionTimeoutSeconds;
        private double autoThreshold;
        private int toolResultLimit;
        private int toolResultKeep;
        private List<RuntimeConfig.McpServer> mcpServers;

        // 创建带新产品目录的默认配置
        private static MutableConfig defaults(Path home) {
            MutableConfig config = new MutableConfig();
            config.host = "127.0.0.1";
            config.port = 7437;
            config.home = home;
            config.logLevel = "INFO";
            config.logFile = home.resolve(".klaude/logs/core.log");
            config.logFormat = "text";
            config.maxSteps = 20;
            config.defaultModel = "claude-sonnet-4-6";
            config.router = "static";
            config.traceEnabled = true;
            config.traceFile = home.resolve(".klaude/traces/daemon.jsonl");
            config.includeLlmPayload = true;
            config.permissionTimeoutSeconds = 60.0;
            config.autoThreshold = 0.0;
            config.toolResultLimit = 8_000;
            config.toolResultKeep = 4_000;
            config.mcpServers = List.of();
            return config;
        }

        // 冻结为完整不可变运行时配置
        private RuntimeConfig freeze() {
            return new RuntimeConfig(
                    host,
                    port,
                    new RuntimeConfig.Logging(logLevel, logFile, logFormat),
                    new RuntimeConfig.Agent(maxSteps),
                    new RuntimeConfig.Llm(defaultModel, router),
                    new RuntimeConfig.Trace(traceEnabled, traceFile, includeLlmPayload),
                    new RuntimeConfig.Permission(permissionTimeoutSeconds),
                    new RuntimeConfig.Compaction(autoThreshold, toolResultLimit, toolResultKeep),
                    new RuntimeConfig.Mcp(mcpServers));
        }
    }
}
