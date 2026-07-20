package io.klaude.protocol;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Objects;

public record EventPushEnvelope(
        @JsonProperty(defaultValue = "event") String kind,
        @JsonProperty(required = true) ObjectNode event) {

    // 校验事件推送 envelope 并填充固定 kind
    public EventPushEnvelope {
        kind = kind == null ? "event" : kind;
        if (!"event".equals(kind)) {
            throw new IllegalArgumentException("kind must be event");
        }
        Objects.requireNonNull(event, "event");
    }
}
