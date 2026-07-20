package io.klaude.extension.profile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.Map;
import java.util.regex.Pattern;
import org.tomlj.Toml;
import org.tomlj.TomlArray;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlTable;

public final class AgentProfileLoader {
    private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9_.-]+");
    private final Path projectRoot;
    private final Path userRoot;
    private final Path builtinRoot;
    private final Map<String, String> builtinDocuments;

    // 初始化 project、user 与 built-in profile 根目录
    public AgentProfileLoader(Path projectRoot, Path userRoot, Path builtinRoot) {
        this(projectRoot, userRoot, builtinRoot, Map.of());
    }

    // 初始化 filesystem roots 与 classpath built-in 文档
    private AgentProfileLoader(
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
    public static AgentProfileLoader production(Path projectRoot, Path userRoot) {
        Map<String, String> documents = new java.util.LinkedHashMap<>();
        for (String name : List.of("executor", "planner", "reviewer")) {
            documents.put(name, readResource("/agents/" + name + ".toml"));
        }
        return new AgentProfileLoader(
                projectRoot, userRoot, projectRoot.resolve(".builtins"), documents);
    }

    // 按 project、user、built-in 优先级加载一个 TOML profile
    public Optional<AgentProfile> load(String name) {
        if (name == null || !SAFE_NAME.matcher(name).matches()) {
            return Optional.empty();
        }
        for (Path root : List.of(projectRoot, userRoot, builtinRoot)) {
            Path path = root.resolve(name + ".toml");
            if (!Files.isRegularFile(path)) {
                continue;
            }
            try {
                return Optional.of(parse(path, name));
            } catch (IOException | RuntimeException ignored) {
                return Optional.empty();
            }
        }
        String document = builtinDocuments.get(name);
        if (document != null) {
            try {
                return Optional.of(parseText(document, name));
            } catch (RuntimeException ignored) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    // 列出 project、user、built-in 中可解析的去重 profile
    public List<AgentProfile> listAll() {
        Set<String> names = new TreeSet<>();
        for (Path root : List.of(projectRoot, userRoot, builtinRoot)) {
            try {
                if (Files.isDirectory(root)) {
                    try (var paths = Files.list(root)) {
                        paths.filter(path -> path.getFileName().toString().endsWith(".toml"))
                                .map(path -> path.getFileName().toString())
                                .map(name -> name.substring(0, name.length() - 5))
                                .filter(name -> SAFE_NAME.matcher(name).matches())
                                .forEach(names::add);
                    }
                }
            } catch (IOException ignored) {
                // Unreadable optional root contributes no profiles.
            }
        }
        names.addAll(builtinDocuments.keySet());
        return names.stream().map(this::load).flatMap(Optional::stream).toList();
    }

    // 使用 Tomlj 严格解析一个 profile 文件
    public AgentProfile parse(Path path, String name) throws IOException {
        TomlParseResult result = Toml.parse(path);
        return parseResult(result, name);
    }

    // 解析一个已读取的 profile 文档
    private static AgentProfile parseText(String text, String name) {
        return parseResult(Toml.parse(text), name);
    }

    // 将 Tomlj 结果转换为不可变 profile
    private static AgentProfile parseResult(TomlParseResult result, String name) {
        if (result.hasErrors()) {
            throw new IllegalArgumentException(result.errors().toString());
        }
        TomlTable agent = result.getTable("agent");
        if (agent == null) {
            agent = result;
        }
        String description = stringValue(agent, "description");
        String systemPrompt = stringValue(agent, "system_prompt").strip();
        String model = stringValue(agent, "model");
        List<String> allowedTools = new ArrayList<>();
        TomlArray tools = agent.getArray("allowed_tools");
        if (tools != null) {
            for (int index = 0; index < tools.size(); index++) {
                Object value = tools.get(index);
                if (!(value instanceof String text)) {
                    throw new IllegalArgumentException("allowed_tools must contain strings");
                }
                allowedTools.add(text);
            }
        }
        return new AgentProfile(name, description, systemPrompt, allowedTools, model);
    }

    // 以 UTF-8 读取一个必需 classpath resource
    private static String readResource(String name) {
        try (var input = AgentProfileLoader.class.getResourceAsStream(name)) {
            if (input == null) {
                throw new IllegalStateException("missing built-in profile resource: " + name);
            }
            return new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new IllegalStateException("cannot read built-in profile resource: " + name, error);
        }
    }

    // 返回可选 TOML 字符串并拒绝非字符串值
    private static String stringValue(TomlTable table, String key) {
        if (!table.contains(key)) {
            return "";
        }
        String value = table.getString(key);
        if (value == null) {
            throw new IllegalArgumentException(key + " must be a string");
        }
        return value;
    }

    // 规范化一个非空根目录路径
    private static Path normalize(Path path) {
        return java.util.Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
    }
}
