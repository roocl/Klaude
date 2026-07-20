package io.klaude.protocol;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Arrays;

public enum SessionStatus {
    ACTIVE("active"),
    WAITING_FOR_INPUT("waiting_for_input"),
    CLOSED("closed");

    private final String wireValue;

    // 保存 session status 的 wire value
    SessionStatus(String wireValue) {
        this.wireValue = wireValue;
    }

    // 将 wire value 解析为 session status
    @JsonCreator
    public static SessionStatus fromWireValue(String value) {
        return Arrays.stream(values())
                .filter(status -> status.wireValue.equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown session status: " + value));
    }

    // 返回 session status 的 wire value
    @JsonValue
    public String wireValue() {
        return wireValue;
    }
}
