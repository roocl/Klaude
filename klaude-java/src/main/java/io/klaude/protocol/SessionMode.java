package io.klaude.protocol;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Arrays;

public enum SessionMode {
    ONE_SHOT("one_shot"),
    CHAT("chat");

    private final String wireValue;

    // 保存 session mode 的 wire value
    SessionMode(String wireValue) {
        this.wireValue = wireValue;
    }

    // 将 wire value 解析为 session mode
    @JsonCreator
    public static SessionMode fromWireValue(String value) {
        return Arrays.stream(values())
                .filter(mode -> mode.wireValue.equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown session mode: " + value));
    }

    // 返回 session mode 的 wire value
    @JsonValue
    public String wireValue() {
        return wireValue;
    }
}
