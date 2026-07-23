package io.klaude.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;

final class DistributionArchiveTest {
    // 功能：验证发布 zip 同时包含 Unix、Windows launcher 与全部运行依赖
    // 设计：读取 Gradle distZip 产物目录树并断言公开入口和 lib 内容存在
    @Test
    void distributionZipContainsLaunchersAndRuntimeLibraries() throws Exception {
        Path archive = Path.of(System.getProperty("klaude.distributionZip"));
        assertThat(archive).isRegularFile();
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            var names = zip.stream().map(entry -> entry.getName()).toList();
            assertThat(names).anyMatch(name -> name.endsWith("/bin/klaude-core-java"));
            assertThat(names).anyMatch(name -> name.endsWith("/bin/klaude-core-java.bat"));
            assertThat(names).anyMatch(name -> name.endsWith("/bin/klaude"));
            assertThat(names).anyMatch(name -> name.endsWith("/bin/klaude.bat"));
            assertThat(names).anyMatch(name -> name.endsWith("/bin/klaude-tui"));
            assertThat(names).anyMatch(name -> name.endsWith("/bin/klaude-tui.bat"));
            assertThat(names).anyMatch(name -> name.matches(".*/lib/klaude-java-.*\\.jar"));
            assertThat(names).anyMatch(name -> name.matches(".*/lib/netty-transport-.*\\.jar"));
        }
    }
}
