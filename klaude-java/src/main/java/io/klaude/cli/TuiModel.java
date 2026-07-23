package io.klaude.cli;

import io.klaude.protocol.Event;
import io.klaude.protocol.LlmTokenEvent;
import io.klaude.protocol.LlmModelSelectedEvent;
import io.klaude.protocol.LlmUsageEvent;
import io.klaude.protocol.LogLineEvent;
import io.klaude.protocol.PermissionRequestedEvent;
import io.klaude.protocol.PermissionGrantedEvent;
import io.klaude.protocol.PermissionDeniedEvent;
import io.klaude.protocol.RunFinishedEvent;
import io.klaude.protocol.RunStartedEvent;
import io.klaude.protocol.SkillInvokedEvent;
import io.klaude.protocol.SubagentFinishedEvent;
import io.klaude.protocol.SubagentStartedEvent;
import io.klaude.protocol.ToolCallFailedEvent;
import io.klaude.protocol.ToolCallFinishedEvent;
import io.klaude.protocol.ToolCallStartedEvent;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.Set;

final class TuiModel {
    private static final int MAX_LINES = 200;
    private final Deque<Entry> transcript = new ArrayDeque<>();
    private final Map<String, String> subagents = new LinkedHashMap<>();
    private final Set<Event> seenEvents = new LinkedHashSet<>();
    private String sessionId = "connecting";
    private String status = "starting";
    private String tokenLine = "";
    private double contextPct;
    private PermissionRequestedEvent permission;
    private boolean busy;
    private boolean connected = true;
    private boolean toolDetails;
    private String runId = "-";
    private String model = "-";
    private int inputTokens;
    private int outputTokens;
    private boolean runActive;

    // 更新当前会话标识
    synchronized void session(String value) {
        sessionId = value;
        status = "ready";
    }

    // 记录用户输入并进入运行状态
    synchronized void userMessage(String content) {
        append("You: " + content);
        status = "running";
        busy = true;
    }

    // 判断当前会话是否正在处理一轮消息
    synchronized boolean busy() {
        return busy;
    }

    // 标记消息请求完成并保留事件给出的最终状态
    synchronized void requestCompleted() {
        busy = runActive;
        if (status.equals("running")) {
            status = "ready";
        }
    }

    // 标记 daemon 连接已经中断
    synchronized void disconnected(String reason) {
        connected = false;
        status = "disconnected";
        append("[error] daemon connection lost: " + reason);
    }

    // 标记重连成功并恢复可交互状态
    synchronized void reconnected(int replayedCount, boolean attachedRun) {
        connected = true;
        if (!attachedRun) {
            busy = false;
            runActive = false;
        }
        status = busy ? "running" : "ready";
        append("[info] reconnected; replayed " + replayedCount + " events");
    }

    // 返回当前 run 作为断线回放锚点
    synchronized String replayRunId() {
        return runId.equals("-") ? null : runId;
    }

    // 记录客户端本地状态消息
    synchronized void notice(String content) {
        flushTokens();
        append("[info] " + content);
    }

    // 切换工具参数与输出详情的可见性
    synchronized boolean toggleToolDetails() {
        toolDetails = !toolDetails;
        return toolDetails;
    }

    // 将 daemon 事件投影到终端界面状态
    synchronized void accept(Event event) {
        if (!seenEvents.add(event)) {
            return;
        }
        while (seenEvents.size() > 2_000) {
            var iterator = seenEvents.iterator();
            iterator.next();
            iterator.remove();
        }
        if (event instanceof RunStartedEvent started) {
            runId = started.runId();
            runActive = true;
            busy = true;
            status = "running";
        } else if (event instanceof LlmTokenEvent token) {
            tokenLine += token.token();
        } else if (event instanceof LlmModelSelectedEvent selected) {
            model = selected.model();
        } else if (event instanceof ToolCallStartedEvent tool) {
            flushTokens();
            append("[tool] " + tool.toolName() + " running");
            appendDetail("  params: " + tool.params());
        } else if (event instanceof ToolCallFinishedEvent tool) {
            append("[tool] " + tool.toolName() + " completed (" + tool.elapsedMs() + "ms)");
            if (!tool.output().isBlank()) {
                appendDetail("  output: " + tool.output());
            }
        } else if (event instanceof ToolCallFailedEvent tool) {
            append("[tool] " + tool.toolName() + " failed: " + tool.errorMessage());
        } else if (event instanceof SkillInvokedEvent skill) {
            append("[skill] /" + skill.skillName());
        } else if (event instanceof PermissionRequestedEvent requested) {
            permission = requested;
            status = "permission required";
        } else if (event instanceof PermissionGrantedEvent granted) {
            clearPermission(granted.toolUseId());
        } else if (event instanceof PermissionDeniedEvent denied) {
            clearPermission(denied.toolUseId());
        } else if (event instanceof LlmUsageEvent usage) {
            contextPct = usage.contextPct();
            inputTokens = usage.inputTokens();
            outputTokens = usage.outputTokens();
        } else if (event instanceof LogLineEvent log && log.level().equalsIgnoreCase("ERROR")) {
            append("[" + log.source() + "] " + log.message());
        } else if (event instanceof SubagentStartedEvent child) {
            subagents.put(child.runId(), "running: " + child.description());
            append("[subagent] " + child.description() + " started");
        } else if (event instanceof SubagentFinishedEvent child) {
            subagents.put(child.runId(), child.status());
            append("[subagent] " + child.runId() + " " + child.status());
        } else if (event instanceof RunFinishedEvent finished) {
            flushTokens();
            status = finished.status();
            busy = false;
            runActive = false;
            append("[run] " + finished.status() + " (" + finished.steps() + " steps)");
        }
    }

    // 清除已由后续事件解决的权限请求
    private void clearPermission(String toolUseId) {
        if (permission != null && permission.toolUseId().equals(toolUseId)) {
            permission = null;
            status = busy ? "running" : "ready";
        }
    }

    // 仅在标识匹配时原子消费待处理权限请求
    synchronized PermissionRequestedEvent takePermission(String toolUseId) {
        if (permission == null || !permission.toolUseId().equals(toolUseId)) {
            return null;
        }
        PermissionRequestedEvent current = permission;
        permission = null;
        return current;
    }

    // 查看当前待处理权限请求但不消费
    synchronized PermissionRequestedEvent permission() {
        return permission;
    }

    // 创建不可变渲染快照
    synchronized Snapshot snapshot() {
        String[] visible = transcript.stream()
                .filter(entry -> toolDetails || !entry.detail())
                .map(Entry::text)
                .toArray(String[]::new);
        String[] children = subagents.entrySet().stream()
                .map(entry -> entry.getKey() + " " + entry.getValue())
                .toArray(String[]::new);
        return new Snapshot(sessionId, status, contextPct, visible,
                tokenLine, permission, busy, connected, toolDetails, runId, model,
                inputTokens, outputTokens, children);
    }

    // 将流式回答收束为一条历史记录
    private void flushTokens() {
        if (!tokenLine.isEmpty()) {
            append("AI: " + tokenLine);
            tokenLine = "";
        }
    }

    // 追加一条记录并限制内存中的历史窗口
    private void append(String line) {
        transcript.addLast(new Entry(line, false));
        while (transcript.size() > MAX_LINES) {
            transcript.removeFirst();
        }
    }

    // 追加一条可折叠的工具详情记录
    private void appendDetail(String line) {
        transcript.addLast(new Entry(line, true));
        while (transcript.size() > MAX_LINES) {
            transcript.removeFirst();
        }
    }

    private record Entry(String text, boolean detail) {
    }

    record Snapshot(
            String sessionId,
            String status,
            double contextPct,
            String[] transcript,
            String tokenLine,
            PermissionRequestedEvent permission,
            boolean busy,
            boolean connected,
            boolean toolDetails,
            String runId,
            String model,
            int inputTokens,
            int outputTokens,
            String[] subagents) {
        // 保存一个一致的界面快照
        Snapshot {
            transcript = transcript.clone();
            subagents = subagents.clone();
        }
    }
}
