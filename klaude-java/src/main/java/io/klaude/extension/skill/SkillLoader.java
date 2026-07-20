package io.klaude.extension.skill;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.Map;
import java.util.regex.Pattern;

public final class SkillLoader {
    private static final Pattern FRONTMATTER = Pattern.compile(
            "\\A---\\R(.*?)\\R---(?:\\R|\\z)", Pattern.DOTALL);
    private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9_.-]+");
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private final Path projectRoot;
    private final Path userRoot;
    private final Path builtinRoot;
    private final Map<String, String> builtinDocuments;

    // 初始化 project、user 与 built-in skill 根目录
    public SkillLoader(Path projectRoot, Path userRoot, Path builtinRoot) {
        this(projectRoot, userRoot, builtinRoot, Map.of());
    }

    // 初始化 filesystem roots 与 classpath built-in 文档
    private SkillLoader(
            Path projectRoot,
            Path userRoot,
            Path builtinRoot,
            Map<String, String> builtinDocuments) {
        this.projectRoot = normalize(projectRoot);
        this.userRoot = normalize(userRoot);
        this.builtinRoot = normalize(builtinRoot);
        this.builtinDocuments = Map.copyOf(builtinDocuments);
    }

    // 创建使用打包 classpath built-ins 的生产 loader
    public static SkillLoader production(Path projectRoot, Path userRoot) {
        Map<String, String> documents = new java.util.LinkedHashMap<>();
        for (String name : List.of("init", "orchestrate", "review", "summarize")) {
            documents.put(name, readResource("/skills/" + name + ".md"));
        }
        return new SkillLoader(projectRoot, userRoot, projectRoot.resolve(".builtins"), documents);
    }

    // 按 project、user、built-in 优先级解析一个 skill
    public Optional<Skill> resolve(String name) {
        if (name == null || !SAFE_NAME.matcher(name).matches()) {
            return Optional.empty();
        }
        for (Path path : candidates(name)) {
            if (!Files.isRegularFile(path)) {
                continue;
            }
            try {
                return Optional.of(parse(path));
            } catch (IOException | RuntimeException ignored) {
                return Optional.empty();
            }
        }
        String document = builtinDocuments.get(name);
        if (document != null) {
            try {
                return Optional.of(parseText(document, name));
            } catch (IOException | RuntimeException ignored) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    // 将 skill prompt 中全部参数占位符替换为调用参数
    public String renderPrompt(Skill skill, String arguments) {
        return java.util.Objects.requireNonNull(skill, "skill")
                .systemPromptTemplate()
                .replace("$ARGUMENTS", java.util.Objects.requireNonNull(arguments, "arguments"));
    }

    // 列出 project、user、built-in 中可解析的去重 skill
    public List<Skill> listAll() {
        Set<String> names = new TreeSet<>();
        for (Path root : List.of(projectRoot, userRoot, builtinRoot)) {
            try {
                if (Files.isDirectory(root)) {
                    try (var paths = Files.walk(root, 2)) {
                        paths.filter(Files::isRegularFile).forEach(path -> {
                            String file = path.getFileName().toString();
                            String name = file.equals("SKILL.md")
                                    ? path.getParent().getFileName().toString()
                                    : stem(file);
                            if (SAFE_NAME.matcher(name).matches()) {
                                names.add(name);
                            }
                        });
                    }
                }
            } catch (IOException ignored) {
                // Unreadable optional root contributes no skills.
            }
        }
        names.addAll(builtinDocuments.keySet());
        return names.stream().map(this::resolve).flatMap(Optional::stream).toList();
    }

    // 使用 YAML frontmatter 与 Markdown 正文解析 UTF-8 skill 文件
    public Skill parse(Path path) throws IOException {
        String text = Files.readString(path, StandardCharsets.UTF_8);
        String defaultName = path.getFileName().toString().equals("SKILL.md")
                ? path.getParent().getFileName().toString()
                : stem(path.getFileName().toString());
        return parseText(text, defaultName);
    }

    // 解析一个已读取的 skill 文档
    private static Skill parseText(String text, String defaultName) throws IOException {
        var matcher = FRONTMATTER.matcher(text);
        if (!matcher.find()) {
            return new Skill(defaultName, "", text.strip(), List.of());
        }
        JsonNode metadata = YAML.readTree(matcher.group(1));
        String name = metadata.path("name").asText(defaultName);
        String description = metadata.path("description").asText("").strip();
        List<String> allowedTools = new ArrayList<>();
        JsonNode tools = metadata.path("allowed_tools");
        if (tools.isArray()) {
            tools.forEach(tool -> allowedTools.add(tool.asText()));
        }
        return new Skill(
                name,
                description,
                text.substring(matcher.end()).strip(),
                allowedTools);
    }

    // 以 UTF-8 读取一个必需 classpath resource
    private static String readResource(String name) {
        try (var input = SkillLoader.class.getResourceAsStream(name)) {
            if (input == null) {
                throw new IllegalStateException("missing built-in skill resource: " + name);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new IllegalStateException("cannot read built-in skill resource: " + name, error);
        }
    }

    // 返回扁平与目录式 skill 候选路径
    private List<Path> candidates(String name) {
        List<Path> paths = new ArrayList<>();
        for (Path root : List.of(projectRoot, userRoot, builtinRoot)) {
            paths.add(root.resolve(name + ".md"));
            paths.add(root.resolve(name).resolve("SKILL.md"));
        }
        return paths;
    }

    // 返回去除扩展名的文件名
    private static String stem(String name) {
        int separator = name.lastIndexOf('.');
        return separator < 0 ? name : name.substring(0, separator);
    }

    // 规范化一个非空根目录路径
    private static Path normalize(Path path) {
        return java.util.Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
    }
}
