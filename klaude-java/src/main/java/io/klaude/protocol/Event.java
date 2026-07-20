package io.klaude.protocol;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = CoreStartedEvent.class, name = "core.started"),
        @JsonSubTypes.Type(value = RunStartedEvent.class, name = "run.started"),
        @JsonSubTypes.Type(value = RunFinishedEvent.class, name = "run.finished"),
        @JsonSubTypes.Type(value = StepStartedEvent.class, name = "step.started"),
        @JsonSubTypes.Type(value = StepFinishedEvent.class, name = "step.finished"),
        @JsonSubTypes.Type(value = ToolCallStartedEvent.class, name = "tool.call_started"),
        @JsonSubTypes.Type(value = ToolCallFinishedEvent.class, name = "tool.call_finished"),
        @JsonSubTypes.Type(value = ToolCallFailedEvent.class, name = "tool.call_failed"),
        @JsonSubTypes.Type(value = LlmTokenEvent.class, name = "llm.token"),
        @JsonSubTypes.Type(value = LlmUsageEvent.class, name = "llm.usage"),
        @JsonSubTypes.Type(value = LlmModelSelectedEvent.class, name = "llm.model_selected"),
        @JsonSubTypes.Type(value = LogLineEvent.class, name = "log.line"),
        @JsonSubTypes.Type(value = SessionCreatedEvent.class, name = "session.created"),
        @JsonSubTypes.Type(value = SessionMessageReceivedEvent.class, name = "session.message_received"),
        @JsonSubTypes.Type(value = SessionWaitingForInputEvent.class, name = "session.waiting_for_input"),
        @JsonSubTypes.Type(value = SessionResumedEvent.class, name = "session.resumed"),
        @JsonSubTypes.Type(value = SessionClosedEvent.class, name = "session.closed"),
        @JsonSubTypes.Type(value = ContextCompactedEvent.class, name = "context.compacted"),
        @JsonSubTypes.Type(value = PermissionRequestedEvent.class, name = "permission.requested"),
        @JsonSubTypes.Type(value = PermissionGrantedEvent.class, name = "permission.granted"),
        @JsonSubTypes.Type(value = PermissionDeniedEvent.class, name = "permission.denied"),
        @JsonSubTypes.Type(value = SubagentStartedEvent.class, name = "subagent.started"),
        @JsonSubTypes.Type(value = SubagentFinishedEvent.class, name = "subagent.finished"),
        @JsonSubTypes.Type(value = SkillInvokedEvent.class, name = "skill.invoked")
})
public sealed interface Event permits
        CoreStartedEvent,
        RunStartedEvent,
        RunFinishedEvent,
        StepStartedEvent,
        StepFinishedEvent,
        ToolCallStartedEvent,
        ToolCallFinishedEvent,
        ToolCallFailedEvent,
        LlmTokenEvent,
        LlmUsageEvent,
        LlmModelSelectedEvent,
        LogLineEvent,
        SessionCreatedEvent,
        SessionMessageReceivedEvent,
        SessionWaitingForInputEvent,
        SessionResumedEvent,
        SessionClosedEvent,
        ContextCompactedEvent,
        PermissionRequestedEvent,
        PermissionGrantedEvent,
        PermissionDeniedEvent,
        SubagentStartedEvent,
        SubagentFinishedEvent,
        SkillInvokedEvent {
}
