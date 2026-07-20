package io.klaude.protocol;

public record SessionCreateCommand(SessionMode mode, String title) implements Command {
    // 填充创建会话命令的默认值
    public SessionCreateCommand {
        mode = mode == null ? SessionMode.CHAT : mode;
        title = title == null ? "" : title;
    }
}
