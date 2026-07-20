package io.klaude.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

final class EnvelopeContractTest {
    // 功能：验证缺少 params 的请求使用空对象默认值并可按 JSON tree 无损往返
    // 设计：只调用公开 mapper 和 envelope record，覆盖 Java 协议入口的最短端到端路径
    @Test
    void requestDefaultsParamsAndRoundTrips() throws Exception {
        var mapper = ProtocolJson.mapper();
        var input = mapper.readTree("""
                {"jsonrpc":"2.0","id":"request-1","method":"core.ping"}
                """);

        var request = mapper.treeToValue(input, JsonRpcRequest.class);
        JsonNode output = mapper.valueToTree(request);

        assertThat(request.params().isObject()).isTrue();
        assertThat(output).isEqualTo(mapper.readTree("""
                {"jsonrpc":"2.0","id":"request-1","method":"core.ping","params":{}}
                """));
    }

    // 功能：验证显式 null params 被拒绝，而不是被误当成字段缺失
    // 设计：直接反序列化最小非法请求，锁定 Pydantic 缺省值与 null 的不同语义
    @Test
    void requestRejectsExplicitNullParams() {
        assertThatThrownBy(() -> ProtocolJson.mapper().readValue("""
                {"jsonrpc":"2.0","id":"request-1","method":"core.ping","params":null}
                """, JsonRpcRequest.class))
                .isInstanceOf(Exception.class);
    }

    // 功能：验证 success result 是必填字段但允许显式 null，并在输出中保留 null
    // 设计：分别解析显式 null 与缺失 result，锁定 JSON-RPC 可空值和字段存在性两个维度
    @Test
    void successRequiresResultButPreservesExplicitNull() throws Exception {
        var mapper = ProtocolJson.mapper();
        var success = mapper.readValue("""
                {"jsonrpc":"2.0","id":"response-1","result":null}
                """, JsonRpcSuccess.class);

        assertThat(success.result()).isNull();
        assertThat(mapper.valueToTree(success).has("result")).isTrue();
        assertThatThrownBy(() -> mapper.readValue("""
                {"jsonrpc":"2.0","id":"response-1"}
                """, JsonRpcSuccess.class))
                .isInstanceOf(Exception.class);
    }

    // 功能：验证错误响应默认 data/null id 与 event envelope 的固定 kind 均可往返
    // 设计：构造两种服务端消息并比较 JSON tree，覆盖响应和异步推送的公共 envelope 边界
    @Test
    void errorAndEventEnvelopesRoundTrip() throws Exception {
        var mapper = ProtocolJson.mapper();
        var error = new JsonRpcError(
                "2.0",
                null,
                new JsonRpcErrorObject(-32700, "Parse error", null));
        var event = mapper.readValue("""
                {"kind":"event","event":{"type":"core.started","version":"0.0.1"}}
                """, EventPushEnvelope.class);
        JsonNode errorTree = mapper.valueToTree(error);

        assertThat(errorTree).isEqualTo(mapper.readTree("""
                {"jsonrpc":"2.0","id":null,"error":{"code":-32700,"message":"Parse error","data":null}}
                """));
        assertThat(event.kind()).isEqualTo("event");
        assertThat(event.event().get("type").textValue()).isEqualTo("core.started");
    }
}
