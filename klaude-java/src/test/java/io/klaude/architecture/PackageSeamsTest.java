package io.klaude.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

final class PackageSeamsTest {
    // 功能：验证协议包只依赖 Java、Jackson 与自身类型
    // 设计：扫描协议源码的 import，让 package seam 在单项目 classpath 中保持可执行约束
    @Test
    void protocolDependsOnlyOnJavaJacksonAndItself() throws IOException {
        Path protocolRoot = Path.of("src", "main", "java", "io", "klaude", "protocol");
        List<String> forbiddenImports;
        try (var files = Files.walk(protocolRoot)) {
            forbiddenImports = files
                    .filter(path -> path.toString().endsWith(".java"))
                    .flatMap(PackageSeamsTest::lines)
                    .map(String::trim)
                    .filter(line -> line.startsWith("import "))
                    .filter(line -> !line.startsWith("import java."))
                    .filter(line -> !line.startsWith("import com.fasterxml.jackson."))
                    .filter(line -> !line.startsWith("import io.klaude.protocol."))
                    .toList();
        }
        assertThat(forbiddenImports).isEmpty();
    }

    // 功能：验证领域包不会反向依赖 daemon 装配包
    // 设计：扫描 daemon 之外的生产源码 import，阻止组合根泄漏到其他 package seams
    @Test
    void domainPackagesDoNotDependOnDaemon() throws IOException {
        Path sourceRoot = Path.of("src", "main", "java", "io", "klaude");
        Path daemonRoot = sourceRoot.resolve("daemon");
        List<Path> offenders;
        try (var files = Files.walk(sourceRoot)) {
            offenders = files
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> !path.startsWith(daemonRoot))
                    .filter(PackageSeamsTest::importsDaemon)
                    .toList();
        }
        assertThat(offenders).isEmpty();
    }

    // 将源码文件安全转换为行流
    private static java.util.stream.Stream<String> lines(Path path) {
        try {
            return Files.readAllLines(path).stream();
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot read " + path, exception);
        }
    }

    // 判断源码是否导入 daemon 装配类型
    private static boolean importsDaemon(Path path) {
        try {
            return Files.readString(path).contains("import io.klaude.daemon.");
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot read " + path, exception);
        }
    }
}
