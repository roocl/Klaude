package io.klaude.protocol;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.RecordComponent;
import java.util.Map;
import java.util.Set;

final class StrictRecordDeserializer<T> extends StdDeserializer<T> {
    private final Class<T> recordType;
    private final Map<String, Object> defaults;
    private final Set<String> nullable;
    private final RecordComponent[] components;
    private final Constructor<T> constructor;

    // 初始化带默认值和可空字段声明的 record 反序列化器
    StrictRecordDeserializer(
            Class<T> recordType,
            Map<String, Object> defaults,
            Set<String> nullable) {
        super(recordType);
        this.recordType = recordType;
        this.defaults = Map.copyOf(defaults);
        this.nullable = Set.copyOf(nullable);
        this.components = recordType.getRecordComponents();
        try {
            Class<?>[] parameterTypes = java.util.Arrays.stream(components)
                    .map(RecordComponent::getType)
                    .toArray(Class<?>[]::new);
            this.constructor = recordType.getDeclaredConstructor(parameterTypes);
        } catch (NoSuchMethodException error) {
            throw new IllegalArgumentException("record canonical constructor not found", error);
        }
    }

    // 严格区分字段缺失、显式 null 和合法 JSON 值后构造 record
    @Override
    public T deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        JsonNode root = parser.currentToken() == JsonToken.END_OBJECT
                ? JsonNodeFactory.instance.objectNode()
                : parser.getCodec().readTree(parser);
        if (!root.isObject()) {
            throw JsonMappingException.from(parser, recordType.getSimpleName() + " must be an object");
        }
        ObjectMapper mapper = (ObjectMapper) parser.getCodec();
        Object[] arguments = new Object[components.length];
        for (int index = 0; index < components.length; index++) {
            RecordComponent component = components[index];
            String name = component.getName();
            String wireName = toSnakeCase(name);
            JsonNode value = root.get(wireName);
            if (value == null) {
                arguments[index] = missingValue(parser, name);
            } else if (value.isNull()) {
                arguments[index] = nullValue(parser, name);
            } else {
                var javaType = mapper.getTypeFactory().constructType(component.getGenericType());
                arguments[index] = mapper.readerFor(javaType).readValue(value);
            }
        }
        try {
            return constructor.newInstance(arguments);
        } catch (InstantiationException | IllegalAccessException | InvocationTargetException error) {
            throw JsonMappingException.from(parser, "cannot construct " + recordType.getSimpleName(), error);
        }
    }

    // 返回缺失字段的默认值、可空值或抛出必填错误
    private Object missingValue(JsonParser parser, String name) throws JsonMappingException {
        if (defaults.containsKey(name)) {
            return defaults.get(name);
        }
        if (nullable.contains(name)) {
            return null;
        }
        throw JsonMappingException.from(parser, name + " is required");
    }

    // 仅允许声明为可空的字段接收显式 JSON null
    private Object nullValue(JsonParser parser, String name) throws JsonMappingException {
        if (nullable.contains(name)) {
            return null;
        }
        throw JsonMappingException.from(parser, name + " must not be null");
    }

    // 将 Java component 名转换为 snake_case wire 字段名
    private static String toSnakeCase(String value) {
        return value.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(java.util.Locale.ROOT);
    }
}
