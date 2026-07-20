package io.klaude.session.task;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Arrays;

public enum TaskStatus {
    PENDING("pending"),
    IN_PROGRESS("in_progress"),
    COMPLETED("completed");

    private final String wireValue;

    // 保存 task status 的 JSON wire value
    TaskStatus(String wireValue) {
        this.wireValue = wireValue;
    }

    // 将 JSON wire value 解析为 task status
    @JsonCreator
    public static TaskStatus fromWireValue(String value) {
        return Arrays.stream(values())
                .filter(status -> status.wireValue.equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown task status: " + value));
    }

    // 返回 task status 的 JSON wire value
    @JsonValue
    public String wireValue() {
        return wireValue;
    }
}
