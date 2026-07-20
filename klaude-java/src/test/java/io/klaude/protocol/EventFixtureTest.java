package io.klaude.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class EventFixtureTest {
    // 功能：验证二十四种事件 fixture 均映射到独立 record 并按 wire JSON tree 无损往返
    // 设计：逐行消费公共 contract，通过真实序列化 bytes 比较，避免依赖 Jackson 数字节点实现
    @Test
    void allEventFixturesRoundTrip() throws Exception {
        var mapper = ProtocolJson.mapper();
        Set<String> recordNames = new LinkedHashSet<>();
        int count = 0;
        try (var stream = Objects.requireNonNull(
                getClass().getResourceAsStream("/fixtures/events.jsonl"));
             var reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                JsonNode expected = mapper.readTree(line);
                Event event = mapper.treeToValue(expected, Event.class);
                JsonNode actual = mapper.readTree(mapper.writeValueAsBytes(event));
                assertThat(actual).isEqualTo(expected);
                recordNames.add(event.getClass().getSimpleName());
                count++;
            }
        }

        assertThat(count).isEqualTo(24);
        assertThat(recordNames).hasSize(24);
    }

    // 功能：验证事件未知类型、缺失字段、错误数值和错误动态 JSON 形状均被拒绝
    // 设计：统一通过 sealed Event 入口解析代表性非法树，覆盖 discriminator、boxed 数字和 ObjectNode
    @Test
    void invalidEventsAreRejected() {
        var mapper = ProtocolJson.mapper();
        var invalid = Set.of(
                "{\"type\":\"unknown.event\"}",
                "{\"type\":\"run.started\",\"run_id\":\"r1\",\"ts\":\"t\"}",
                "{\"type\":\"step.started\",\"run_id\":\"r1\",\"step\":\"1\",\"ts\":\"t\"}",
                "{\"type\":\"step.started\",\"run_id\":\"r1\",\"step\":null,\"ts\":\"t\"}",
                "{\"type\":\"llm.usage\",\"run_id\":\"r1\",\"output_tokens\":1,"
                        + "\"cache_read_input_tokens\":0,\"cache_creation_input_tokens\":0,\"ts\":\"t\"}",
                "{\"type\":\"tool.call_started\",\"run_id\":\"r1\","
                        + "\"tool_use_id\":\"t1\",\"tool_name\":\"read\",\"params\":[],\"ts\":\"t\"}");

        for (String json : invalid) {
            Throwable failure = catchThrowable(() -> mapper.readValue(json, Event.class));
            assertThat(failure)
                    .as("invalid event must fail: %s", json)
                    .isNotNull();
        }
    }
}
