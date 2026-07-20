package io.klaude.session;

@FunctionalInterface
public interface SessionPromptResolver {
    // 将用户原始消息解析为一次 run 的 prompt 配置
    SessionPrompt resolve(String content);
}
