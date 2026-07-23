package io.klaude.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import org.junit.jupiter.api.Test;

final class ResultContractTest {
    private record ResultCase(String input, String expected, Class<?> type) {
    }

    // 功能：验证十二种 result 独立建模并按 JSON tree 往返，默认字段会显式输出
    // 设计：表驱动覆盖所有 result 公共类型，避免创建包含全部可空字段的万能 DTO
    @Test
    void allResultsRoundTrip() throws Exception {
        var cases = List.of(
                new ResultCase(
                        "{\"server_version\":\"0.0.1\",\"uptime_ms\":42,\"received_at\":\"2026-07-18T00:00:00Z\"}",
                        "{\"server_version\":\"0.0.1\",\"uptime_ms\":42,\"received_at\":\"2026-07-18T00:00:00Z\"}",
                        PongResult.class),
                new ResultCase("{\"run_id\":\"run-1\"}", "{\"run_id\":\"run-1\"}", AgentRunResult.class),
                new ResultCase(
                        "{\"subscription_id\":\"sub-1\"}",
                        "{\"subscription_id\":\"sub-1\",\"replayed_count\":0}",
                        EventSubscribeResult.class),
                new ResultCase(
                        "{\"session_id\":\"session-1\",\"status\":\"active\"}",
                        "{\"session_id\":\"session-1\",\"status\":\"active\"}",
                        SessionCreateResult.class),
                new ResultCase("{\"run_id\":\"run-2\"}", "{\"run_id\":\"run-2\"}", SessionSendMessageResult.class),
                new ResultCase(
                        "{\"messages\":[{\"role\":\"user\",\"content\":\"你好\"}]}",
                        "{\"messages\":[{\"role\":\"user\",\"content\":\"你好\"}]}",
                        SessionGetHistoryResult.class),
                new ResultCase("{\"status\":\"closed\"}", "{\"status\":\"closed\"}", SessionCloseResult.class),
                new ResultCase("{}", "{\"ok\":true}", PermissionRespondResult.class),
                new ResultCase(
                        "{\"summary_tokens\":100,\"saved_tokens\":900}",
                        "{\"summary_tokens\":100,\"saved_tokens\":900}",
                        SessionCompactResult.class),
                new ResultCase(
                        "{\"sessions\":[{\"session_id\":\"session-1\",\"mode\":\"chat\","
                                + "\"status\":\"active\",\"title\":\"demo\","
                                + "\"updated_at\":\"2026-07-20T00:00:00Z\",\"last_run_id\":\"\"}]}",
                        "{\"sessions\":[{\"session_id\":\"session-1\",\"mode\":\"chat\","
                                + "\"status\":\"active\",\"title\":\"demo\","
                                + "\"updated_at\":\"2026-07-20T00:00:00Z\",\"last_run_id\":\"\"}]}",
                        SessionListResult.class),
                new ResultCase(
                        "{\"skills\":[{\"name\":\"review\",\"description\":\"Review code\","
                                + "\"allowed_tools\":[\"read_file\"]}]}",
                        "{\"skills\":[{\"name\":\"review\",\"description\":\"Review code\","
                                + "\"allowed_tools\":[\"read_file\"]}]}",
                        SkillListResult.class),
                new ResultCase(
                        "{\"cancelled\":true}",
                        "{\"cancelled\":true}",
                        RunCancelResult.class));

        var mapper = ProtocolJson.mapper();
        for (ResultCase resultCase : cases) {
            Object result = mapper.readValue(resultCase.input(), resultCase.type());
            JsonNode actual = mapper.readTree(mapper.writeValueAsBytes(result));
            assertThat(actual).isEqualTo(mapper.readTree(resultCase.expected()));
        }
    }
}
