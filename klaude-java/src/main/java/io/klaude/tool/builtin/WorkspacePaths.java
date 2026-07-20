package io.klaude.tool.builtin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

final class WorkspacePaths {
    private final Path root;

    // 保存真实 workspace root
    WorkspacePaths(Path root) throws IOException {
        this.root = root.toAbsolutePath().normalize().toRealPath();
    }

    // 解析必须存在的 workspace 内路径并阻止 symlink escape
    Path existing(String value) throws IOException {
        Path candidate = lexical(value);
        Path real = candidate.toRealPath();
        requireInside(real);
        return real;
    }

    // 解析可新建的 workspace 内路径并验证最近存在父目录
    Path writable(String value) throws IOException {
        Path candidate = lexical(value);
        Path existing = candidate;
        while (existing != null && !Files.exists(existing)) {
            existing = existing.getParent();
        }
        if (existing == null) {
            throw new IOException("path has no existing parent");
        }
        requireInside(existing.toRealPath());
        if (Files.exists(candidate)) {
            requireInside(candidate.toRealPath());
        }
        return candidate;
    }

    // 对输入路径做 lexical normalize 和 root containment
    private Path lexical(String value) throws IOException {
        Path input = Path.of(value);
        Path candidate = input.isAbsolute()
                ? input.toAbsolutePath().normalize()
                : root.resolve(input).normalize();
        requireInside(candidate);
        return candidate;
    }

    // 拒绝不在 workspace root 下的路径
    private void requireInside(Path candidate) throws IOException {
        if (!candidate.startsWith(root)) {
            throw new IOException("path is outside workspace: " + candidate);
        }
    }

    // 返回真实 workspace root
    Path root() {
        return root;
    }
}
