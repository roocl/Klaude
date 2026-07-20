package io.klaude.extension;

import static org.assertj.core.api.Assertions.assertThat;

import io.klaude.extension.profile.AgentProfileLoader;
import io.klaude.extension.skill.SkillLoader;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class BuiltinExtensionResourcesTest {
    // 功能：验证生产 loader 从 classpath 读取打包后的内建 skill 与 profile
    // 设计：使用空 project/user 根目录，只通过公开 production 工厂解析已打包资源
    @Test
    void loadsBuiltinsFromClasspath(@TempDir Path temp) {
        var skills = SkillLoader.production(
                temp.resolve("project/skills"), temp.resolve("user/skills"));
        var profiles = AgentProfileLoader.production(
                temp.resolve("project/agents"), temp.resolve("user/agents"));

        assertThat(skills.resolve("review")).get()
                .extracting(skill -> skill.description())
                .asString().contains("Review code");
        assertThat(skills.listAll()).extracting(skill -> skill.name())
                .contains("init", "orchestrate", "review", "summarize");
        assertThat(profiles.load("planner")).get()
                .extracting(profile -> profile.systemPrompt())
                .asString().contains("concise plan");
        assertThat(profiles.listAll()).extracting(profile -> profile.name())
                .contains("executor", "planner", "reviewer");
    }
}
