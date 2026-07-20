package io.klaude.daemon;

import io.klaude.daemon.config.RuntimeConfig;
import io.klaude.extension.profile.AgentProfile;
import io.klaude.extension.profile.AgentProfileLoader;
import io.klaude.extension.skill.Skill;
import io.klaude.extension.skill.SkillLoader;
import io.klaude.extension.subagent.BackgroundSubagentRegistry;
import io.klaude.mcp.McpConnector;
import io.klaude.mcp.McpServerManager;
import io.klaude.mcp.McpServerSpec;
import io.klaude.session.SessionPrompt;
import io.klaude.tool.Tool;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

public final class DaemonExtensionRuntime implements AutoCloseable {
    private final SkillLoader skills;
    private final AgentProfileLoader profiles;
    private final McpServerManager mcp;
    private final BackgroundSubagentRegistry backgrounds = new BackgroundSubagentRegistry();

    // 组合 skill、profile、MCP 与后台 child 生命周期边界
    public DaemonExtensionRuntime(
            Path projectSkills,
            Path userSkills,
            Path builtinSkills,
            Path projectProfiles,
            Path userProfiles,
            Path builtinProfiles,
            McpConnector connector) {
        this(
                new SkillLoader(projectSkills, userSkills, builtinSkills),
                new AgentProfileLoader(projectProfiles, userProfiles, builtinProfiles),
                connector);
    }

    // 组合已创建的 loader 与 MCP 生命周期边界
    private DaemonExtensionRuntime(
            SkillLoader skills,
            AgentProfileLoader profiles,
            McpConnector connector) {
        this.skills = java.util.Objects.requireNonNull(skills, "skills");
        this.profiles = java.util.Objects.requireNonNull(profiles, "profiles");
        this.mcp = new McpServerManager(connector);
    }

    // 创建使用 .klaude overrides 与 classpath built-ins 的生产 runtime
    public static DaemonExtensionRuntime production(
            Path workspace, Path home, McpConnector connector) {
        return new DaemonExtensionRuntime(
                SkillLoader.production(
                        workspace.resolve(".klaude/skills"), home.resolve(".klaude/skills")),
                AgentProfileLoader.production(
                        workspace.resolve(".klaude/agents"), home.resolve(".klaude/agents")),
                connector);
    }

    // 映射 daemon 配置并启动全部 MCP servers
    public CompletionStage<Void> start(List<RuntimeConfig.McpServer> servers) {
        return mcp.startAll(servers.stream().map(DaemonExtensionRuntime::toSpec).toList());
    }

    // 将 slash skill 消息展开为 session run prompt
    public SessionPrompt resolvePrompt(String content) {
        java.util.Objects.requireNonNull(content, "content");
        if (!content.startsWith("/") || content.length() == 1) {
            return SessionPrompt.unchanged(content);
        }
        String invocation = content.substring(1);
        int separator = firstWhitespace(invocation);
        String name = separator < 0 ? invocation : invocation.substring(0, separator);
        String arguments = separator < 0 ? "" : invocation.substring(separator).stripLeading();
        Optional<Skill> resolved = skills.resolve(name);
        if (resolved.isEmpty()) {
            return SessionPrompt.unchanged(content);
        }
        Skill skill = resolved.orElseThrow();
        return new SessionPrompt(
                skills.renderPrompt(skill, arguments),
                skill.systemPromptTemplate(),
                skill.allowedTools(),
                name,
                arguments);
    }

    // 返回一个可选 agent profile
    public Optional<AgentProfile> profile(String name) {
        return profiles.load(name);
    }

    // 返回已发现 MCP tools 的不可变快照
    public List<Tool> tools() {
        return mcp.tools();
    }

    // 返回共享后台 child registry
    public BackgroundSubagentRegistry backgrounds() {
        return backgrounds;
    }

    // 先取消所有 child，再关闭 MCP connections
    @Override
    public void close() {
        backgrounds.close();
        mcp.close();
    }

    // 将 daemon MCP server 配置映射为模块 spec
    private static McpServerSpec toSpec(RuntimeConfig.McpServer server) {
        if (server.transport().equals("stdio")) {
            List<String> command = new ArrayList<>();
            command.add(server.command());
            command.addAll(server.args());
            return McpServerSpec.stdio(server.name(), command, server.environment());
        }
        return McpServerSpec.tcp(server.name(), server.host(), server.port());
    }

    // 返回首个 Unicode whitespace 的索引
    private static int firstWhitespace(String value) {
        for (int index = 0; index < value.length(); index++) {
            if (Character.isWhitespace(value.charAt(index))) {
                return index;
            }
        }
        return -1;
    }
}
