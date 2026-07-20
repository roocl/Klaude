package io.klaude.tool.builtin;

@FunctionalInterface
public interface NoteService {
    // 向指定 session 追加一条 run 笔记
    void append(String sessionId, String content, String runId) throws Exception;
}
