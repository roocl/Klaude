package io.klaude.extension.skill;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class SkillLoaderTest {
    // 功能：验证项目目录式 skill 覆盖内建同名项并解析 YAML block 与 allowed tools
    // 设计：三个注入根目录只写 built-in 扁平文件和 project SKILL.md，通过公开 resolve 观察覆盖结果
    @Test
    void projectDirectorySkillOverridesBuiltinAndParsesFrontmatter(@TempDir Path temp)
            throws Exception {
        Path project = temp.resolve("project");
        Path user = temp.resolve("user");
        Path builtin = temp.resolve("builtin");
        Files.createDirectories(project.resolve("review"));
        Files.createDirectories(user);
        Files.createDirectories(builtin);
        Files.writeString(
                builtin.resolve("review.md"),
                "---\nname: review\ndescription: builtin\n---\nbuiltin body\n",
                StandardCharsets.UTF_8);
        Files.writeString(
                project.resolve("review/SKILL.md"),
                """
                ---
                name: review
                description: >
                  Local review
                  workflow
                allowed_tools:
                  - read_file
                  - bash
                ---
                Review $ARGUMENTS carefully.
                """,
                StandardCharsets.UTF_8);
        var loader = new SkillLoader(project, user, builtin);

        Skill skill = loader.resolve("review").orElseThrow();

        assertThat(skill.description()).isEqualTo("Local review workflow");
        assertThat(skill.allowedTools()).containsExactly("read_file", "bash");
        assertThat(skill.systemPromptTemplate()).isEqualTo("Review $ARGUMENTS carefully.");
        assertThat(loader.renderPrompt(skill, "src/Main.java"))
                .isEqualTo("Review src/Main.java carefully.");
    }

    // 功能：验证 loader 列出多个根目录的去重 skill
    // 设计：在 project 与 builtin 写入不同 skill，通过公开 listAll 观察稳定排序
    @Test
    void listsResolvedSkillsAcrossRoots(@TempDir Path temp) throws Exception {
        Path project = temp.resolve("project");
        Path builtin = temp.resolve("builtin");
        Files.createDirectories(project);
        Files.createDirectories(builtin);
        Files.writeString(project.resolve("review.md"), "# local", StandardCharsets.UTF_8);
        Files.writeString(builtin.resolve("summarize.md"), "# builtin", StandardCharsets.UTF_8);

        var loader = new SkillLoader(project, temp.resolve("user"), builtin);

        assertThat(loader.listAll()).extracting(Skill::name)
                .containsExactly("review", "summarize");
    }
}
