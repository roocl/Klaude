package io.klaude.transport;

import static org.assertj.core.api.Assertions.assertThat;

import io.klaude.protocol.ProtocolJson;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.junit.jupiter.api.Test;

final class SubscriptionMatcherTest {
    // 功能：验证 topic glob 与 run scope 匹配结果符合冻结平台契约
    // 设计：逐条读取公共 fixture，只通过公开 matcher 输入 event type/topics 或 run ID/scope
    @Test
    void matchesPhaseZeroSubscriptionFixtures() throws Exception {
        var mapper = ProtocolJson.mapper();
        try (var stream = Objects.requireNonNull(
                getClass().getResourceAsStream("/fixtures/subscription-matching.json"))) {
            var cases = mapper.readTree(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
            for (var fixture : cases) {
                boolean actual;
                if (fixture.path("kind").asText().equals("topic")) {
                    java.util.List<String> topics = mapper.convertValue(
                            fixture.path("topics"),
                            mapper.getTypeFactory().constructCollectionType(
                                    java.util.List.class, String.class));
                    actual = SubscriptionMatcher.matchesTopic(
                            fixture.path("event_type").asText(), topics);
                } else {
                    actual = SubscriptionMatcher.matchesScope(
                            fixture.path("run_id").asText(null), fixture.path("scope").asText());
                }
                assertThat(actual)
                        .as(fixture.path("name").asText())
                        .isEqualTo(fixture.path("matches").asBoolean());
            }
        }
    }
}
