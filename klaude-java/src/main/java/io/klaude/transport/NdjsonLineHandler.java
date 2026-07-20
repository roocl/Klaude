package io.klaude.transport;

import java.util.concurrent.CompletionStage;
import java.util.concurrent.CompletableFuture;

@FunctionalInterface
public interface NdjsonLineHandler {
    // 异步处理一条完整 UTF-8 NDJSON 请求行
    CompletionStage<Void> handle(NdjsonConnection connection, String line) throws Exception;

    // 在输入行超限时完成可选响应，server 随后关闭连接
    default CompletionStage<Void> handleOversized(NdjsonConnection connection) {
        return CompletableFuture.completedFuture(null);
    }
}
