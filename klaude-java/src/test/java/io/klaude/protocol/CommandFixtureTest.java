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

final class CommandFixtureTest {
    // 功能：验证九种命令 fixture 均映射到独立 record 并按 JSON tree 无损往返
    // 设计：从公共 contract 逐行读取，避免在 Java 测试中复制字段和默认值
    @Test
    void allCommandFixturesRoundTrip() throws Exception {
        var mapper = ProtocolJson.mapper();
        Set<String> recordNames = new LinkedHashSet<>();
        int count = 0;
        try (var stream = Objects.requireNonNull(
                getClass().getResourceAsStream("/fixtures/commands.jsonl"));
             var reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                JsonNode expected = mapper.readTree(line);
                Command command = mapper.treeToValue(expected, Command.class);
                JsonNode actual = mapper.valueToTree(command);
                assertThat(actual).isEqualTo(expected);
                recordNames.add(command.getClass().getSimpleName());
                count++;
            }
        }

        assertThat(count).isEqualTo(9);
        assertThat(recordNames).containsExactlyInAnyOrder(
                "PingCommand",
                "AgentRunCommand",
                "EventSubscribeCommand",
                "SessionCreateCommand",
                "SessionSendMessageCommand",
                "SessionGetHistoryCommand",
                "SessionCloseCommand",
                "PermissionRespondCommand",
                "SessionCompactCommand");
    }

    // 功能：验证未知判别值、缺少必填字段、错误类型和非法 enum 均被拒绝
    // 设计：通过 sealed Command 公共入口解析代表性非法树，覆盖 discriminator 与 record 校验边界
    @Test
    void invalidCommandsAreRejected() {
        var mapper = ProtocolJson.mapper();
        var invalid = Set.of(
                "{\"client\":\"x\"}",
                "{\"type\":\"unknown.command\"}",
                "{\"type\":\"agent.run\"}",
                "{\"type\":\"agent.run\",\"goal\":42}",
                "{\"type\":\"session.create\",\"mode\":\"invalid\",\"title\":\"\"}");

        for (String json : invalid) {
            Throwable failure = catchThrowable(() -> mapper.readValue(json, Command.class));
            assertThat(failure)
                    .as("invalid command must fail: %s", json)
                    .isNotNull();
        }
    }
}
