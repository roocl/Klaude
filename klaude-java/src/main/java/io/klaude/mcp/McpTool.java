package io.klaude.mcp;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.klaude.tool.Tool;
import io.klaude.tool.ToolContext;
import io.klaude.tool.ToolErrorType;
import io.klaude.tool.ToolResult;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public final class McpTool implements Tool {
    private final McpToolCaller caller;
    private final String serverName;
    private final McpToolDefinition definition;

    // 初始化带 server namespace 的 MCP tool adapter
    public McpTool(
            McpToolCaller caller, String serverName, McpToolDefinition definition) {
        this.caller = java.util.Objects.requireNonNull(caller, "caller");
        this.serverName = java.util.Objects.requireNonNull(serverName, "serverName");
        this.definition = java.util.Objects.requireNonNull(definition, "definition");
    }

    // 返回 server__remote 格式的稳定工具名
    @Override
    public String name() {
        return serverName + "__" + definition.name();
    }

    // 返回远端描述或 server fallback 描述
    @Override
    public String description() {
        return definition.description().isBlank()
                ? "MCP tool from " + serverName
                : definition.description();
    }

    // 返回远端 input schema 的防御性副本
    @Override
    public ObjectNode inputSchema() {
        return definition.inputSchema();
    }

    // 调用远端工具并将全部异常转换为 runtime error result
    @Override
    public CompletionStage<ToolResult> execute(ToolContext context, ObjectNode params) {
        try {
            return caller.callTool(definition.name(), params.deepCopy())
                    .handle((content, error) -> error == null
                            ? ToolResult.success(content)
                            : ToolResult.failure(
                                    errorMessage(unwrap(error)), ToolErrorType.RUNTIME_ERROR));
        } catch (Throwable error) {
            return CompletableFuture.completedFuture(ToolResult.failure(
                    errorMessage(error), ToolErrorType.RUNTIME_ERROR));
        }
    }

    // 构造包含 server/tool 上下文的错误文本
    private String errorMessage(Throwable error) {
        if (error instanceof McpServerUnavailableException) {
            return "mcp server '" + serverName + "' unavailable: " + error.getMessage();
        }
        if (error instanceof McpToolException) {
            return "mcp tool '" + name() + "' error: " + error.getMessage();
        }
        return "mcp tool '" + name() + "' unexpected error: " + error.getMessage();
    }

    // 解开 completion wrappers 以保留 MCP 错误分类
    private static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while ((current instanceof java.util.concurrent.CompletionException
                        || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }
}
