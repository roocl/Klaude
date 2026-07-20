package io.klaude.tool.permission;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;
import org.tomlj.Toml;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlTable;

public final class PermissionPolicyStore {
    private static final Pattern BARE_KEY = Pattern.compile("[A-Za-z0-9_-]+");
    private final Path path;

    // 初始化一个显式 policy.toml 持久化路径
    public PermissionPolicyStore(Path path) {
        this.path = path.toAbsolutePath().normalize();
    }

    // 从 always 节加载有效 allow/deny 决策并忽略其他内容
    public Map<String, PolicyDecision> load() throws IOException {
        if (!Files.exists(path)) {
            return Map.of();
        }
        TomlParseResult result = Toml.parse(path);
        if (result.hasErrors()) {
            throw new IOException("cannot parse policy: " + path + ": " + result.errors());
        }
        TomlTable always = result.getTable("always");
        if (always == null) {
            return Map.of();
        }
        Map<String, PolicyDecision> policy = new HashMap<>();
        for (String tool : always.keySet()) {
            Object raw = always.get(tool);
            if (raw instanceof String value) {
                PolicyDecision.fromWireValue(value)
                        .ifPresent(decision -> policy.put(tool, decision));
            }
        }
        return Map.copyOf(policy);
    }

    // 将 allow/deny 决策按工具名排序并原子写入 UTF-8 policy.toml
    public void save(Map<String, PolicyDecision> policy) throws IOException {
        Path parent = path.getParent();
        Files.createDirectories(parent);
        StringBuilder toml = new StringBuilder()
                .append("# ~/.klaude/policy.toml\n")
                .append("# Managed by the klaude daemon.\n\n")
                .append("[always]\n");
        for (var entry : new TreeMap<>(policy).entrySet()) {
            toml.append(tomlKey(entry.getKey()))
                    .append(" = \"")
                    .append(entry.getValue().wireValue())
                    .append("\"\n");
        }
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        try {
            Files.writeString(
                    temporary,
                    toml,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING);
            Files.move(
                    temporary,
                    path,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    // 将工具名编码为合法 TOML bare key 或 quoted key
    private static String tomlKey(String tool) {
        if (BARE_KEY.matcher(tool).matches()) {
            return tool;
        }
        return "\"" + tool.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
