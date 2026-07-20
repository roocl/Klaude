package io.klaude.extension.profile;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class AgentProfileLoaderTest {
    // 功能：验证项目 Agent profile 覆盖 built-in 并完整解析 TOML 字段
    // 设计：注入三个隔离根目录写同名 profile，通过公开 load 观察 project 版本
    @Test
    void projectProfileOverridesBuiltinAndParsesToml(@TempDir Path temp) throws Exception {
        Path project = temp.resolve("project");
        Path user = temp.resolve("user");
        Path builtin = temp.resolve("builtin");
        Files.createDirectories(project);
        Files.createDirectories(user);
        Files.createDirectories(builtin);
        Files.writeString(
                builtin.resolve("planner.toml"),
                "[agent]\ndescription=\"builtin\"\nsystem_prompt=\"builtin\"\n"
                        + "allowed_tools=[\"read_file\"]\nmodel=\"\"\n",
                StandardCharsets.UTF_8);
        Files.writeString(
                project.resolve("planner.toml"),
                "[agent]\ndescription=\"local planner\"\n"
                        + "system_prompt=\"Plan carefully.\"\n"
                        + "allowed_tools=[\"read_file\",\"list_dir\"]\n"
                        + "model=\"test-model\"\n",
                StandardCharsets.UTF_8);

        AgentProfile profile = new AgentProfileLoader(project, user, builtin)
                .load("planner").orElseThrow();

        assertThat(profile.description()).isEqualTo("local planner");
        assertThat(profile.systemPrompt()).isEqualTo("Plan carefully.");
        assertThat(profile.allowedTools()).containsExactly("read_file", "list_dir");
        assertThat(profile.model()).isEqualTo("test-model");
    }

    // 功能：验证 profile loader 列出多个根目录的去重 profile
    // 设计：写入 project 与 builtin profile，通过公开 listAll 观察稳定排序
    @Test
    void listsResolvedProfilesAcrossRoots(@TempDir Path temp) throws Exception {
        Path project = temp.resolve("project");
        Path builtin = temp.resolve("builtin");
        Files.createDirectories(project);
        Files.createDirectories(builtin);
        Files.writeString(project.resolve("planner.toml"), "[agent]\ndescription='p'\n");
        Files.writeString(builtin.resolve("reviewer.toml"), "[agent]\ndescription='r'\n");

        var loader = new AgentProfileLoader(project, temp.resolve("user"), builtin);

        assertThat(loader.listAll()).extracting(AgentProfile::name)
                .containsExactly("planner", "reviewer");
    }
}
