package io.klaude.protocol;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = PingCommand.class, name = "core.ping"),
        @JsonSubTypes.Type(value = AgentRunCommand.class, name = "agent.run"),
        @JsonSubTypes.Type(value = EventSubscribeCommand.class, name = "event.subscribe"),
        @JsonSubTypes.Type(value = SessionCreateCommand.class, name = "session.create"),
        @JsonSubTypes.Type(value = SessionSendMessageCommand.class, name = "session.send_message"),
        @JsonSubTypes.Type(value = SessionGetHistoryCommand.class, name = "session.get_history"),
        @JsonSubTypes.Type(value = SessionCloseCommand.class, name = "session.close"),
        @JsonSubTypes.Type(value = PermissionRespondCommand.class, name = "permission.respond"),
        @JsonSubTypes.Type(value = SessionCompactCommand.class, name = "session.compact")
})
public sealed interface Command permits
        PingCommand,
        AgentRunCommand,
        EventSubscribeCommand,
        SessionCreateCommand,
        SessionSendMessageCommand,
        SessionGetHistoryCommand,
        SessionCloseCommand,
        PermissionRespondCommand,
        SessionCompactCommand {
}
