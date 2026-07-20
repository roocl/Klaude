package io.klaude.protocol;

public record SkillInvokedEvent(
        String skillName,
        String arguments,
        String runId,
        String ts) implements Event {

    // 校验技能调用事件字段
    public SkillInvokedEvent {
        ProtocolChecks.required(skillName, "skillName");
        ProtocolChecks.required(arguments, "arguments");
        ProtocolChecks.required(runId, "runId");
        ProtocolChecks.required(ts, "ts");
    }
}
