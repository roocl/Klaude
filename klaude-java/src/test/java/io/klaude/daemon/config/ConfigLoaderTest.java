package io.klaude.daemon.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ConfigLoaderTest {
    // 功能：验证默认、用户 TOML、项目 TOML、dotenv 和系统环境按顺序覆盖
    // 设计：在隔离 home/cwd 写五层不同端口，调用公开 loader 后只观察最终不可变配置
    @Test
    void loadsCompletePriorityChain(@TempDir Path temp) throws Exception {
        Path home = temp.resolve("home");
        Path cwd = temp.resolve("project");
        Files.createDirectories(home.resolve(".klaude"));
        Files.createDirectories(cwd.resolve(".klaude"));
        Files.writeString(
                home.resolve(".klaude/config.toml"),
                "[core]\nport = 6000\n",
                StandardCharsets.UTF_8);
        Files.writeString(
                cwd.resolve(".klaude/config.toml"),
                "[core]\nport = 6100\n",
                StandardCharsets.UTF_8);
        Files.writeString(
                cwd.resolve(".env"),
                "KLAUDE_PORT=6200\n",
                StandardCharsets.UTF_8);

        RuntimeConfig config = new ConfigLoader().load(
                cwd,
                home,
                Map.of("KLAUDE_PORT", "6300"));

        assertThat(config.port()).isEqualTo(6300);
        assertThat(config.host()).isEqualTo("127.0.0.1");
        assertThat(config.logging().file()).isEqualTo(home.resolve(".klaude/logs/core.log"));
        assertThat(config.trace().file()).isEqualTo(home.resolve(".klaude/traces/daemon.jsonl"));
    }

    // 功能：向 daemon 暴露 dotenv 与进程变量合并后的完整环境
    // 设计：在 dotenv 写 API Key，再用进程 Map 覆盖并验证系统变量优先
    @Test
    void resolvesDotenvForRuntimeSecrets(@TempDir Path temp) throws Exception {
        Path cwd = temp.resolve("project");
        Files.createDirectories(cwd);
        Files.writeString(
                cwd.resolve(".env"),
                "ANTHROPIC_API_KEY=from-dotenv\nKLAUDE_PORT=6200\n",
                StandardCharsets.UTF_8);
        var loader = new ConfigLoader();

        Map<String, String> dotenv = loader.resolveEnvironment(cwd, Map.of());
        Map<String, String> overridden = loader.resolveEnvironment(
                cwd, Map.of("ANTHROPIC_API_KEY", "from-process"));

        assertThat(dotenv).containsEntry("ANTHROPIC_API_KEY", "from-dotenv");
        assertThat(overridden).containsEntry("ANTHROPIC_API_KEY", "from-process");
    }

    // 功能：验证 dotenv 指定显式配置后只读取该 TOML，并相对 daemon cwd 解析路径
    // 设计：默认用户/项目 TOML 都写端口，显式文件只写 host，断言端口回到内建默认值
    @Test
    void explicitConfigFromDotenvReplacesDefaultTomlChain(@TempDir Path temp) throws Exception {
        Path home = temp.resolve("home");
        Path cwd = temp.resolve("project");
        Files.createDirectories(home.resolve(".klaude"));
        Files.createDirectories(cwd.resolve(".klaude"));
        Files.writeString(home.resolve(".klaude/config.toml"), "[core]\nport=6000\n");
        Files.writeString(cwd.resolve(".klaude/config.toml"), "[core]\nport=6100\n");
        Files.writeString(cwd.resolve("custom.toml"), "[core]\nhost=\"0.0.0.0\"\n");
        Files.writeString(cwd.resolve(".env"), "KLAUDE_CONFIG=custom.toml\n");

        RuntimeConfig config = new ConfigLoader().load(cwd, home, Map.of());

        assertThat(config.host()).isEqualTo("0.0.0.0");
        assertThat(config.port()).isEqualTo(7437);
    }

    // 功能：验证所有 TOML 配置域、MCP server 和路径展开均映射到不可变配置
    // 设计：单个显式 TOML 覆盖全部字段，避免优先级噪声并检查相对 cwd 与 home 两种路径
    @Test
    void loadsAllTomlSectionsAndMcpServers(@TempDir Path temp) throws Exception {
        Path home = temp.resolve("home");
        Path cwd = temp.resolve("project");
        Files.createDirectories(home);
        Files.createDirectories(cwd);
        Path configPath = cwd.resolve("full.toml");
        Files.writeString(configPath, """
                [core]
                host = "0.0.0.0"
                port = 8123
                [logging]
                level = "DEBUG"
                file = "logs/custom.log"
                format = "json"
                [agent]
                max_steps = 30
                [llm]
                default_model = "scripted-model"
                router = "rule_based"
                [trace]
                enabled = false
                file = "~/trace.jsonl"
                include_llm_payload = false
                [permission]
                timeout_s = 12.5
                [compaction]
                auto_threshold = 0.75
                tool_result_limit = 9000
                tool_result_keep = 4500
                [[mcp.servers]]
                name = "local"
                transport = "stdio"
                command = "tool.exe"
                args = ["--stdio"]
                host = "localhost"
                port = 3001
                [mcp.servers.env]
                TOKEN = "value"
                """, StandardCharsets.UTF_8);

        RuntimeConfig config = new ConfigLoader().load(
                cwd,
                home,
                Map.of("KLAUDE_CONFIG", configPath.toString()));

        assertThat(config.logging()).isEqualTo(new RuntimeConfig.Logging(
                "DEBUG", cwd.resolve("logs/custom.log"), "json"));
        assertThat(config.agent().maxSteps()).isEqualTo(30);
        assertThat(config.llm().defaultModel()).isEqualTo("scripted-model");
        assertThat(config.trace()).isEqualTo(new RuntimeConfig.Trace(
                false, home.resolve("trace.jsonl"), false));
        assertThat(config.permission().timeoutSeconds()).isEqualTo(12.5);
        assertThat(config.compaction()).isEqualTo(new RuntimeConfig.Compaction(0.75, 9000, 4500));
        assertThat(config.mcp().servers()).containsExactly(new RuntimeConfig.McpServer(
                "local",
                "stdio",
                "tool.exe",
                java.util.List.of("--stdio"),
                Map.of("TOKEN", "value"),
                "localhost",
                3001));
    }

    // 功能：验证所有受支持环境变量覆盖配置并正确解析布尔、数字和路径
    // 设计：从空临时目录加载，仅注入环境 Map，排除 TOML/dotenv 干扰并覆盖全部环境入口
    @Test
    void environmentOverridesAllSupportedFields(@TempDir Path temp) {
        Path home = temp.resolve("home");
        Path cwd = temp.resolve("project");
        RuntimeConfig config = new ConfigLoader().load(cwd, home, Map.ofEntries(
                Map.entry("KLAUDE_HOST", "0.0.0.0"),
                Map.entry("KLAUDE_PORT", "9000"),
                Map.entry("KLAUDE_LOG_LEVEL", "WARNING"),
                Map.entry("KLAUDE_LOG_FILE", "logs/env.log"),
                Map.entry("KLAUDE_LOG_FORMAT", "json"),
                Map.entry("KLAUDE_MAX_STEPS", "40"),
                Map.entry("KLAUDE_LLM_DEFAULT_MODEL", "env-model"),
                Map.entry("KLAUDE_TRACE_ENABLED", "false"),
                Map.entry("KLAUDE_TRACE_FILE", "~/env-trace.jsonl"),
                Map.entry("KLAUDE_TRACE_INCLUDE_LLM_PAYLOAD", "no"),
                Map.entry("KLAUDE_PERMISSION_TIMEOUT_S", "5.5"),
                Map.entry("KLAUDE_COMPACT_THRESHOLD", "0.8"),
                Map.entry("KLAUDE_COMPACT_TOOL_LIMIT", "10000"),
                Map.entry("KLAUDE_COMPACT_TOOL_KEEP", "5000")));

        assertThat(config.logging()).isEqualTo(new RuntimeConfig.Logging(
                "WARNING", cwd.resolve("logs/env.log"), "json"));
        assertThat(config.agent().maxSteps()).isEqualTo(40);
        assertThat(config.llm().defaultModel()).isEqualTo("env-model");
        assertThat(config.trace()).isEqualTo(new RuntimeConfig.Trace(
                false, home.resolve("env-trace.jsonl"), false));
        assertThat(config.permission().timeoutSeconds()).isEqualTo(5.5);
        assertThat(config.compaction()).isEqualTo(new RuntimeConfig.Compaction(0.8, 10000, 5000));
    }

    // 功能：验证未知键、错误类型、非法范围和无效 MCP server 均被拒绝
    // 设计：逐个覆盖同一显式临时文件，调用公开 loader 并要求统一抛出 ConfigException
    @Test
    void rejectsUnknownAndInvalidToml(@TempDir Path temp) throws Exception {
        Path home = temp.resolve("home");
        Path cwd = temp.resolve("project");
        Files.createDirectories(home);
        Files.createDirectories(cwd);
        Path configPath = cwd.resolve("invalid.toml");
        var invalidCases = java.util.List.of(
                "[unknown]\nvalue=true\n",
                "[core]\nextra=true\n",
                "[core]\nport=\"wrong\"\n",
                "[agent]\nmax_steps=0\n",
                "[permission]\ntimeout_s=-1\n",
                "[compaction]\nauto_threshold=1.1\n",
                "[[mcp.servers]]\nname=\"bad\"\ntransport=\"udp\"\n",
                "[[mcp.servers]]\ntransport=\"stdio\"\n");

        for (String invalid : invalidCases) {
            Files.writeString(configPath, invalid, StandardCharsets.UTF_8);
            assertThatThrownBy(() -> new ConfigLoader().load(
                    cwd,
                    home,
                    Map.of("KLAUDE_CONFIG", configPath.toString())))
                    .as("invalid TOML must fail: %s", invalid)
                    .isInstanceOf(ConfigException.class);
        }
    }
}
