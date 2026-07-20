package io.klaude.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import org.junit.jupiter.api.Test;

final class DefaultValueContractTest {
    // 功能：验证命令缺省字段使用参考默认值并在输出中显式保留
    // 设计：通过 sealed Command 入口解析省略字段的最小请求，覆盖所有 command 默认值
    @Test
    void commandDefaultsMatchContract() throws Exception {
        var mapper = ProtocolJson.mapper();

        var subscribe = (EventSubscribeCommand) mapper.readValue(
                "{\"type\":\"event.subscribe\",\"topics\":[]}", Command.class);
        var create = (SessionCreateCommand) mapper.readValue(
                "{\"type\":\"session.create\"}", Command.class);
        var compact = (SessionCompactCommand) mapper.readValue(
                "{\"type\":\"session.compact\",\"session_id\":\"s1\"}", Command.class);

        assertThat(subscribe.scope()).isEqualTo("global");
        assertThat(subscribe.replayFromRun()).isNull();
        assertThat(create.mode()).isEqualTo(SessionMode.CHAT);
        assertThat(create.title()).isEmpty();
        assertThat(compact.focus()).isEmpty();
    }

    // 功能：验证事件缺省字段使用参考默认值，可空 reason 保持 null
    // 设计：分别解析三个包含默认字段的事件类型，覆盖字符串、整数、浮点和可空值
    @Test
    void eventDefaultsMatchContract() throws Exception {
        var mapper = ProtocolJson.mapper();
        var finished = (RunFinishedEvent) mapper.readValue(
                "{\"type\":\"run.finished\",\"run_id\":\"r1\",\"status\":\"success\","
                        + "\"steps\":1,\"ts\":\"t\"}", Event.class);
        var tool = (ToolCallFinishedEvent) mapper.readValue(
                "{\"type\":\"tool.call_finished\",\"run_id\":\"r1\","
                        + "\"tool_use_id\":\"t1\",\"tool_name\":\"read\","
                        + "\"elapsed_ms\":1,\"ts\":\"t\"}", Event.class);
        var failed = (ToolCallFailedEvent) mapper.readValue(
                "{\"type\":\"tool.call_failed\",\"run_id\":\"r1\","
                        + "\"tool_use_id\":\"t1\",\"tool_name\":\"read\","
                        + "\"error_class\":\"runtime_error\",\"error_message\":\"x\","
                        + "\"elapsed_ms\":1,\"ts\":\"t\"}", Event.class);
        var usage = (LlmUsageEvent) mapper.readValue(
                "{\"type\":\"llm.usage\",\"run_id\":\"r1\",\"input_tokens\":1,"
                        + "\"output_tokens\":1,\"cache_read_input_tokens\":0,"
                        + "\"cache_creation_input_tokens\":0,\"ts\":\"t\"}", Event.class);

        assertThat(finished.reason()).isNull();
        assertThat(tool.output()).isEmpty();
        assertThat(failed.attempt()).isEqualTo(1);
        assertThat(usage.contextPct()).isEqualTo(0.0);
    }

    // 功能：验证非可空默认字段显式 null 时被拒绝，而不是被当作字段缺失
    // 设计：从命令、结果和事件各选一个默认字段，确保统一 mapper 保持缺失/null 边界
    @Test
    void explicitNullDoesNotTriggerNonNullableDefaults() {
        var mapper = ProtocolJson.mapper();
        var cases = new Object[][] {
                {"{\"type\":\"event.subscribe\",\"topics\":[],\"scope\":null}", Command.class},
                {"{\"subscription_id\":\"sub-1\",\"replayed_count\":null}", EventSubscribeResult.class},
                {"{\"type\":\"tool.call_finished\",\"run_id\":\"r1\","
                        + "\"tool_use_id\":\"t1\",\"tool_name\":\"read\","
                        + "\"elapsed_ms\":1,\"output\":null,\"ts\":\"t\"}", Event.class}
        };

        for (Object[] invalidCase : cases) {
            String json = (String) invalidCase[0];
            Class<?> type = (Class<?>) invalidCase[1];
            Throwable failure = catchThrowable(() -> mapper.readValue(json, type));
            assertThat(failure)
                    .as("explicit null must fail: %s", json)
                    .isNotNull();
        }
    }
}
