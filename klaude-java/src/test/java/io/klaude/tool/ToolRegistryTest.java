package io.klaude.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.klaude.protocol.ProtocolJson;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

final class ToolRegistryTest {
    // 功能：验证工具注册、同名覆盖、名称查找和不可变 provider definitions
    // 设计：注册两个同名 fake tool，观察引用覆盖，并尝试修改 definitions list/schema 副本
    @Test
    void registersReplacesAndExportsImmutableDefinitions() {
        ToolRegistry registry = new ToolRegistry();
        Tool first = fakeTool("first");
        Tool replacement = fakeTool("replacement");

        registry.register(first);
        registry.register(replacement);

        assertThat(registry.get("fake")).containsSame(replacement);
        assertThat(registry.get("missing")).isEmpty();
        var definitions = registry.definitions();
        assertThat(definitions).hasSize(1);
        assertThat(definitions.getFirst().name()).isEqualTo("fake");
        assertThat(definitions.getFirst().description()).isEqualTo("replacement");
        assertThat(definitions.getFirst().inputSchema().path("type").asText()).isEqualTo("object");
        assertThatThrownBy(() -> definitions.add(definitions.getFirst()))
                .isInstanceOf(UnsupportedOperationException.class);
        definitions.getFirst().inputSchema().put("type", "string");
        assertThat(registry.definitions().getFirst().inputSchema().path("type").asText())
                .isEqualTo("object");
    }

    // 创建一个带最小 object schema 的 fake tool
    private static Tool fakeTool(String description) {
        return new Tool() {
            // 返回固定工具名
            @Override
            public String name() {
                return "fake";
            }

            // 返回测试传入描述
            @Override
            public String description() {
                return description;
            }

            // 返回最小 object input schema
            @Override
            public com.fasterxml.jackson.databind.node.ObjectNode inputSchema() {
                return ProtocolJson.mapper().createObjectNode()
                        .put("type", "object")
                        .set("properties", ProtocolJson.mapper().createObjectNode());
            }

            // 返回固定成功结果
            @Override
            public java.util.concurrent.CompletionStage<ToolResult> execute(
                    ToolContext context,
                    com.fasterxml.jackson.databind.node.ObjectNode params) {
                return CompletableFuture.completedFuture(ToolResult.success("ok"));
            }
        };
    }
}
