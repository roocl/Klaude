package io.klaude.session.task;

import io.klaude.protocol.ProtocolJson;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Pattern;

public final class TaskManager {
    private static final Pattern TASK_FILE = Pattern.compile("task_[0-9]+\\.json");
    private final Path root;
    private final Clock clock;
    private int nextId;

    // 初始化 task 根目录与可注入时间来源
    public TaskManager(Path root, Clock clock) throws IOException {
        this.root = root.toAbsolutePath().normalize();
        this.clock = clock;
        Files.createDirectories(this.root);
        this.nextId = listAll().stream().mapToInt(Task::id).max().orElse(0) + 1;
    }

    // 读取全部有效 task 文件并按 ID 升序返回
    public List<Task> listAll() throws IOException {
        List<Task> tasks = new ArrayList<>();
        try (var paths = Files.list(root)) {
            for (Path path : paths.filter(this::isTaskFile).toList()) {
                tasks.add(readTask(path));
            }
        }
        tasks.sort(Comparator.comparingInt(Task::id));
        return List.copyOf(tasks);
    }

    // 创建待处理 task 并以递增 ID 原子写入 UTF-8 JSON
    public synchronized Task create(
            String subject, String description, List<Integer> blockedBy) throws IOException {
        for (int dependency : blockedBy) {
            get(dependency);
        }
        String now = Instant.now(clock).toString();
        Task task = new Task(
                nextId++, subject, description, TaskStatus.PENDING, blockedBy, now, now);
        save(task);
        return task;
    }

    // 按 ID 读取一个 task，不存在时拒绝请求
    public Task get(int taskId) throws IOException {
        Path path = taskPath(taskId);
        if (!Files.exists(path)) {
            throw new IllegalArgumentException("task " + taskId + " not found");
        }
        return readTask(path);
    }

    // 更新 task 状态并在完成时清理其他 task 对它的依赖
    public synchronized Task updateStatus(int taskId, TaskStatus status) throws IOException {
        return update(taskId, status, List.of(), List.of());
    }

    // 同时更新 task 状态与阻塞依赖
    public synchronized Task update(
            int taskId,
            TaskStatus status,
            List<Integer> addBlockedBy,
            List<Integer> removeBlockedBy) throws IOException {
        Task current = get(taskId);
        var dependencies = new LinkedHashSet<>(current.blockedBy());
        dependencies.addAll(addBlockedBy);
        dependencies.removeAll(removeBlockedBy);
        TaskStatus updatedStatus = status == null ? current.status() : status;
        Task updated = new Task(
                current.id(),
                current.subject(),
                current.description(),
                updatedStatus,
                List.copyOf(dependencies),
                current.createdAt(),
                Instant.now(clock).toString());
        save(updated);
        if (updatedStatus == TaskStatus.COMPLETED) {
            clearDependency(taskId);
        }
        return updated;
    }

    // 将全部 task 格式化为稳定的状态摘要
    public String formatList() throws IOException {
        List<Task> tasks = listAll();
        if (tasks.isEmpty()) {
            return "No tasks.";
        }
        List<String> lines = new ArrayList<>();
        for (Task task : tasks) {
            String marker = switch (task.status()) {
                case PENDING -> "[ ]";
                case IN_PROGRESS -> "[>]";
                case COMPLETED -> "[x]";
            };
            String blocked = task.blockedBy().isEmpty()
                    ? ""
                    : " (blocked by: " + task.blockedBy() + ")";
            lines.add(marker + " #" + task.id() + ": " + task.subject() + blocked);
        }
        return String.join("\n", lines);
    }

    // 从所有其他 task 中移除已完成的依赖 ID
    private void clearDependency(int completedId) throws IOException {
        for (Task task : listAll()) {
            if (task.id() == completedId || !task.blockedBy().contains(completedId)) {
                continue;
            }
            List<Integer> dependencies = task.blockedBy().stream()
                    .filter(id -> id != completedId)
                    .toList();
            save(new Task(
                    task.id(),
                    task.subject(),
                    task.description(),
                    task.status(),
                    dependencies,
                    task.createdAt(),
                    Instant.now(clock).toString()));
        }
    }

    // 通过临时文件和同目录原子替换保存一个 UTF-8 task JSON
    private void save(Task task) throws IOException {
        Path target = taskPath(task.id());
        Path temporary = root.resolve(target.getFileName() + ".tmp");
        String json = ProtocolJson.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(task) + "\n";
        try {
            Files.writeString(
                    temporary,
                    json,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING);
            Files.move(
                    temporary,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    // 返回指定 task ID 的 JSON 文件路径
    private Path taskPath(int taskId) {
        return root.resolve("task_" + taskId + ".json");
    }

    // 判断路径是否为严格 task_<数字>.json 文件名
    private boolean isTaskFile(Path path) {
        return Files.isRegularFile(path) && TASK_FILE.matcher(path.getFileName().toString()).matches();
    }

    // 兼容读取 UTF-8 与既有 GBK task JSON
    private static Task readTask(Path path) throws IOException {
        byte[] bytes = Files.readAllBytes(path);
        String json;
        try {
            json = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException ignored) {
            json = java.nio.charset.Charset.forName("GBK").decode(ByteBuffer.wrap(bytes)).toString();
        }
        return ProtocolJson.mapper().readValue(json, Task.class);
    }
}
