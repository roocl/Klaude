package io.klaude.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class CliEndpointTest {
    // 功能：使用环境变量覆盖项目 TOML 中的 daemon 地址
    // 设计：创建隔离配置文件并验证与 daemon 相同的覆盖顺序
    @Test
    void environmentOverridesProjectConfig(@TempDir Path temp) throws Exception {
        Path home = temp.resolve("home");
        Files.createDirectories(temp.resolve(".klaude"));
        Files.createDirectories(home);
        Files.writeString(temp.resolve(".klaude/config.toml"),
                "[core]\nhost = \"project\"\nport = 7000\n");

        CliEndpoint endpoint = CliEndpoint.load(
                temp, home, Map.of("KLAUDE_HOST", "environment", "KLAUDE_PORT", "8000"));

        assertThat(endpoint.host()).isEqualTo("environment");
        assertThat(endpoint.port()).isEqualTo(8000);
    }
}
