package io.klaude.session.task;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class TaskManagerTest {
    // 功能：验证 Java 能读取冻结的既有 CP936 task JSON fixtures
    // 设计：用 fixture 目录构造公开 manager，并按 ID 断言状态、中文 subject 与依赖清理结果
    @Test
    void readsExistingTaskFixtures() throws Exception {
        TaskManager manager = new TaskManager(fixturePath("/fixtures/tasks"), Clock.systemUTC());

        var tasks = manager.listAll();

        assertThat(tasks).extracting(Task::id).containsExactly(1, 2);
        assertThat(tasks).extracting(Task::subject).containsExactly("准备契约", "验证契约");
        assertThat(tasks).extracting(Task::status)
                .containsExactly(TaskStatus.COMPLETED, TaskStatus.PENDING);
        assertThat(tasks.get(1).blockedBy()).isEmpty();
    }

    // 功能：验证 Java 以 UTF-8 原子写 task JSON 且完成任务会清理其他任务依赖
    // 设计：固定时钟创建中文依赖链并完成前置任务，再从公开接口和严格 UTF-8 decoder 检查结果
    @Test
    void createsUtf8TasksAndClearsCompletedDependencies(@TempDir Path temp) throws Exception {
        Clock clock = Clock.fixed(Instant.parse("2026-07-19T10:15:30Z"), ZoneOffset.UTC);
        TaskManager manager = new TaskManager(temp.resolve("tasks"), clock);

        Task first = manager.create("准备契约", "", List.of());
        Task second = manager.create("验证契约", "", List.of(first.id()));
        manager.updateStatus(first.id(), TaskStatus.COMPLETED);

        assertThat(first.id()).isEqualTo(1);
        assertThat(second.id()).isEqualTo(2);
        assertThat(manager.get(2).blockedBy()).isEmpty();
        byte[] bytes = Files.readAllBytes(temp.resolve("tasks/task_2.json"));
        String json = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString();
        assertThat(json).contains("验证契约");
    }

    // 功能：验证 task update 可同时修改状态、增加依赖并移除旧依赖
    // 设计：创建三个任务，对第三个执行组合更新并通过公开 get 重读
    @Test
    void updatesStatusAndBlockingDependenciesTogether(@TempDir Path temp) throws Exception {
        Clock clock = Clock.fixed(Instant.parse("2026-07-19T10:15:30Z"), ZoneOffset.UTC);
        TaskManager manager = new TaskManager(temp.resolve("tasks"), clock);
        Task first = manager.create("任务一", "", List.of());
        Task second = manager.create("任务二", "", List.of());
        Task third = manager.create("任务三", "", List.of(first.id()));

        Task updated = manager.update(
                third.id(),
                TaskStatus.IN_PROGRESS,
                List.of(second.id()),
                List.of(first.id()));

        assertThat(updated.status()).isEqualTo(TaskStatus.IN_PROGRESS);
        assertThat(updated.blockedBy()).containsExactly(second.id());
        assertThat(manager.get(third.id())).isEqualTo(updated);
    }

    // 功能：验证 task list 摘要包含状态标记、ID、Unicode subject 和阻塞依赖
    // 设计：创建一个 in_progress 前置任务和一个被阻塞任务，观察稳定文本行
    @Test
    void formatsStableTaskListSummary(@TempDir Path temp) throws Exception {
        TaskManager manager = new TaskManager(temp.resolve("tasks"), Clock.systemUTC());
        Task first = manager.create("准备契约", "", List.of());
        manager.updateStatus(first.id(), TaskStatus.IN_PROGRESS);
        manager.create("验证契约", "", List.of(first.id()));

        assertThat(manager.formatList()).isEqualTo(
                "[>] #1: 准备契约\n[ ] #2: 验证契约 (blocked by: [1])");
    }

    // 将测试 classpath 中的 fixture 目录转换为文件系统路径
    private static Path fixturePath(String resource) throws Exception {
        URI uri = Objects.requireNonNull(TaskManagerTest.class.getResource(resource)).toURI();
        return Path.of(uri);
    }
}
