package io.klaude.tool;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.concurrent.CompletionStage;

@FunctionalInterface
public interface PermissionGateway {
    // 异步检查一个已通过 schema 校验的工具调用权限
    CompletionStage<PermissionOutcome> check(ToolContext context, Tool tool, ObjectNode params);
}
