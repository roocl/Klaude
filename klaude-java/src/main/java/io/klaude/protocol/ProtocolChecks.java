package io.klaude.protocol;

import java.util.Objects;

final class ProtocolChecks {
    // 禁止实例化协议校验工具类
    private ProtocolChecks() {
    }

    // 校验协议必填字段并返回原值
    static <T> T required(T value, String name) {
        return Objects.requireNonNull(value, name);
    }
}
