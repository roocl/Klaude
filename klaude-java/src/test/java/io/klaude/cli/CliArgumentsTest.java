package io.klaude.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

final class CliArgumentsTest {
    // 功能：解析包含空格的 run goal
    // 设计：把 --goal 后的全部参数拼为一个目标字符串
    @Test
    void parsesRunGoal() {
        CliArguments arguments = CliArguments.parse(
                new String[] {"run", "--goal", "inspect", "project"});

        assertThat(arguments.command()).isEqualTo("run");
        assertThat(arguments.goal()).isEqualTo("inspect project");
    }

    // 功能：拒绝缺少 goal 的 run 命令
    // 设计：直接覆盖 CLI 参数边界而不启动网络连接
    @Test
    void rejectsMissingGoal() {
        assertThatThrownBy(() -> CliArguments.parse(new String[] {"run", "--goal"}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requires --goal");
    }

    // 功能：解析 cancel 的 run ID 与无参数 trace
    // 设计：覆盖阶段三新增的两个顶层命令语法
    @Test
    void parsesLifecycleCommands() {
        assertThat(CliArguments.parse(new String[] {"cancel", "run-1"}).goal())
                .isEqualTo("run-1");
        assertThat(CliArguments.parse(new String[] {"trace"}).command())
                .isEqualTo("trace");
    }
}
