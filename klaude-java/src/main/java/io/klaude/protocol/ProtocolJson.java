package io.klaude.protocol;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.type.LogicalType;
import java.util.Map;
import java.util.Set;

public final class ProtocolJson {
    private static final ObjectMapper MAPPER = createMapper();

    // 禁止实例化协议 JSON 工具类
    private ProtocolJson() {
    }

    // 创建严格区分 JSON 标量类型的协议 mapper
    private static ObjectMapper createMapper() {
        ObjectMapper mapper = JsonMapper.builder()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
                .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .build();
        mapper.coercionConfigFor(LogicalType.Textual)
                .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail);
        SimpleModule defaults = new SimpleModule("strict-record-defaults");
        defaults.addDeserializer(
                EventSubscribeCommand.class,
                new StrictRecordDeserializer<>(
                        EventSubscribeCommand.class,
                        Map.of("scope", "global"),
                        Set.of("replayFromRun")));
        defaults.addDeserializer(
                SessionCreateCommand.class,
                new StrictRecordDeserializer<>(
                        SessionCreateCommand.class,
                        Map.of("mode", SessionMode.CHAT, "title", ""),
                        Set.of()));
        defaults.addDeserializer(
                SessionCompactCommand.class,
                new StrictRecordDeserializer<>(
                        SessionCompactCommand.class,
                        Map.of("focus", ""),
                        Set.of()));
        defaults.addDeserializer(
                EventSubscribeResult.class,
                new StrictRecordDeserializer<>(
                        EventSubscribeResult.class,
                        Map.of("replayedCount", 0),
                        Set.of()));
        defaults.addDeserializer(
                PermissionRespondResult.class,
                new StrictRecordDeserializer<>(
                        PermissionRespondResult.class,
                        Map.of("ok", Boolean.TRUE),
                        Set.of()));
        defaults.addDeserializer(
                ToolCallFinishedEvent.class,
                new StrictRecordDeserializer<>(
                        ToolCallFinishedEvent.class,
                        Map.of("output", ""),
                        Set.of()));
        defaults.addDeserializer(
                ToolCallFailedEvent.class,
                new StrictRecordDeserializer<>(
                        ToolCallFailedEvent.class,
                        Map.of("attempt", 1),
                        Set.of()));
        defaults.addDeserializer(
                LlmUsageEvent.class,
                new StrictRecordDeserializer<>(
                        LlmUsageEvent.class,
                        Map.of("contextPct", 0.0),
                        Set.of()));
        mapper.registerModule(defaults);
        return mapper;
    }

    // 返回线程安全且不可变配置的协议 ObjectMapper
    public static ObjectMapper mapper() {
        return MAPPER;
    }
}
