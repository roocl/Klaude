package io.klaude.protocol;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;

final class EnvelopeFields {
    // 禁止实例化 envelope 字段工具类
    private EnvelopeFields() {
    }

    // 读取必填文本字段并拒绝缺失、null 和错误类型
    static String requiredText(JsonParser parser, JsonNode root, String field)
            throws JsonMappingException {
        JsonNode value = root.get(field);
        if (value == null || !value.isTextual()) {
            throw JsonMappingException.from(parser, field + " must be a string");
        }
        return value.textValue();
    }
}
