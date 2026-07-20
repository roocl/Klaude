package io.klaude.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.klaude.protocol.ProtocolJson;
import io.klaude.tool.ToolContext;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

final class McpToolTest {
    // 功能：验证 MCP tool 使用 server 前缀、暴露 schema 并把 client 文本包装为成功结果
    // 设计：fake caller 捕获远端名和 arguments，通过标准 Tool execute 观察定义与结果
    @Test
    void prefixesNameAndInvokesRemoteToolThroughStandardBoundary() throws Exception {
        var calledName = new java.util.concurrent.atomic.AtomicReference<String>();
        var calledArguments = new java.util.concurrent.atomic.AtomicReference<com.fasterxml.jackson.databind.node.ObjectNode>();
        McpToolCaller caller = (name, arguments) -> {
            calledName.set(name);
            calledArguments.set(arguments.deepCopy());
            return CompletableFuture.completedFuture("remote result");
        };
        var schema = ProtocolJson.mapper().createObjectNode()
                .put("type", "object");
        schema.set("properties", ProtocolJson.mapper().createObjectNode()
                .set("path", ProtocolJson.mapper().createObjectNode().put("type", "string")));
        var tool = new McpTool(
                caller,
                "filesystem",
                new McpToolDefinition("read", "Read remote file", schema));
        var params = ProtocolJson.mapper().createObjectNode().put("path", "doc.txt");

        var result = tool.execute(
                        new ToolContext(Path.of("."), "session", "run", "call"), params)
                .toCompletableFuture().get();

        assertThat(tool.name()).isEqualTo("filesystem__read");
        assertThat(tool.description()).isEqualTo("Read remote file");
        assertThat(tool.inputSchema()).isEqualTo(schema);
        assertThat(calledName).hasValue("read");
        assertThat(calledArguments.get()).isEqualTo(params);
        assertThat(result.content()).isEqualTo("remote result");
        assertThat(result.isError()).isFalse();
    }
}
