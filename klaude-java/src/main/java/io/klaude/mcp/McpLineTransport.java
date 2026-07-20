package io.klaude.mcp;

import java.time.Duration;
import java.util.concurrent.CompletionStage;

public interface McpLineTransport extends AutoCloseable {
    // 异步写出一条不含行终止符的 UTF-8 JSON 行
    CompletionStage<Void> writeLine(String line);

    // 在指定时限内异步读取一条完整 UTF-8 行
    CompletionStage<String> readLine(Duration timeout);

    // 关闭底层连接、流或进程
    @Override
    void close();
}
