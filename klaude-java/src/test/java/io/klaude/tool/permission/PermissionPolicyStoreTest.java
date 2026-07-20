package io.klaude.tool.permission;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class PermissionPolicyStoreTest {
    // 功能：验证冻结 policy fixture 只加载 always 节内有效 allow/deny 决策
    // 设计：通过公开 store 读取冻结 TOML，断言忽略 ask 值与其他 section
    @Test
    void readsPhaseZeroPolicyFixture() throws Exception {
        Path fixture = fixturePath("/fixtures/policy/policy.toml");

        Map<String, PolicyDecision> policy = new PermissionPolicyStore(fixture).load();

        assertThat(policy).containsExactlyInAnyOrderEntriesOf(Map.of(
                "bash", PolicyDecision.DENY,
                "read_file", PolicyDecision.ALLOW));
    }

    // 功能：验证 policy 决策以 UTF-8、工具名字典序和原子替换方式持久化
    // 设计：向临时路径保存无序 Map，检查文本顺序后用新 store 重新加载比较
    @Test
    void savesSortedUtf8PolicyAndReloads(@TempDir Path temp) throws Exception {
        Path path = temp.resolve("config/policy.toml");
        PermissionPolicyStore store = new PermissionPolicyStore(path);
        Map<String, PolicyDecision> expected = Map.of(
                "write_file", PolicyDecision.ALLOW,
                "bash", PolicyDecision.DENY,
                "read_file", PolicyDecision.ALLOW);

        store.save(expected);

        String toml = Files.readString(path, StandardCharsets.UTF_8);
        assertThat(toml.indexOf("bash =")).isLessThan(toml.indexOf("read_file ="));
        assertThat(toml.indexOf("read_file =")).isLessThan(toml.indexOf("write_file ="));
        assertThat(new PermissionPolicyStore(path).load()).isEqualTo(expected);
        assertThat(path.resolveSibling("policy.toml.tmp")).doesNotExist();
    }

    // 将测试 classpath 中的 fixture 转换为文件系统路径
    private static Path fixturePath(String resource) throws Exception {
        URI uri = Objects.requireNonNull(PermissionPolicyStoreTest.class.getResource(resource)).toURI();
        return Path.of(uri);
    }
}
