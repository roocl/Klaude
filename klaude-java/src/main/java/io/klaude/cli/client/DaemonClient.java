package io.klaude.cli.client;

import io.klaude.protocol.AgentRunCommand;
import io.klaude.protocol.AgentRunResult;
import io.klaude.protocol.EventSubscribeCommand;
import io.klaude.protocol.EventSubscribeResult;
import io.klaude.protocol.PermissionRespondCommand;
import io.klaude.protocol.PermissionRespondResult;
import io.klaude.protocol.PingCommand;
import io.klaude.protocol.PongResult;
import io.klaude.protocol.RunCancelCommand;
import io.klaude.protocol.RunCancelResult;
import io.klaude.protocol.SessionCreateCommand;
import io.klaude.protocol.SessionCreateResult;
import io.klaude.protocol.SessionMode;
import io.klaude.protocol.SessionCompactCommand;
import io.klaude.protocol.SessionCompactResult;
import io.klaude.protocol.SessionGetHistoryCommand;
import io.klaude.protocol.SessionGetHistoryResult;
import io.klaude.protocol.SessionListCommand;
import io.klaude.protocol.SessionListResult;
import io.klaude.protocol.SessionSendMessageCommand;
import io.klaude.protocol.SessionSendMessageResult;
import io.klaude.protocol.SkillListCommand;
import io.klaude.protocol.SkillListResult;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public final class DaemonClient {
    private final NdjsonRpcClient rpc;

    // 保存底层 RPC 连接
    public DaemonClient(NdjsonRpcClient rpc) {
        this.rpc = java.util.Objects.requireNonNull(rpc, "rpc");
    }

    // 请求 daemon 健康状态
    public CompletableFuture<PongResult> ping() {
        return rpc.request("core.ping", new PingCommand("cli"), PongResult.class);
    }

    // 订阅 CLI 所需的全部实时事件
    public CompletableFuture<EventSubscribeResult> subscribeAll() {
        return subscribeAll(null);
    }

    // 订阅全部实时事件并可从指定 run 回放历史
    public CompletableFuture<EventSubscribeResult> subscribeAll(String replayFromRun) {
        return rpc.request(
                "event.subscribe",
                new EventSubscribeCommand(List.of("*"), "global", replayFromRun),
                EventSubscribeResult.class);
    }

    // 启动一个独立 agent goal
    public CompletableFuture<AgentRunResult> run(String goal) {
        return rpc.request("agent.run", new AgentRunCommand(goal), AgentRunResult.class);
    }

    // 请求取消一个 daemon 后台 run
    public CompletableFuture<RunCancelResult> cancelRun(String runId) {
        return rpc.request("run.cancel", new RunCancelCommand(runId), RunCancelResult.class);
    }

    // 创建一个持续聊天 session
    public CompletableFuture<SessionCreateResult> createChatSession(String title) {
        return rpc.request(
                "session.create",
                new SessionCreateCommand(SessionMode.CHAT, title),
                SessionCreateResult.class);
    }

    // 向 session 发送普通文本或 slash skill
    public CompletableFuture<SessionSendMessageResult> sendMessage(
            String sessionId, String content) {
        return rpc.request(
                "session.send_message",
                new SessionSendMessageCommand(sessionId, content),
                SessionSendMessageResult.class);
    }

    // 列出 daemon 持久化的全部 session
    public CompletableFuture<SessionListResult> listSessions() {
        return rpc.request("session.list", new SessionListCommand(), SessionListResult.class);
    }

    // 读取一个 session 的模型消息历史
    public CompletableFuture<SessionGetHistoryResult> getHistory(String sessionId) {
        return rpc.request(
                "session.get_history",
                new SessionGetHistoryCommand(sessionId),
                SessionGetHistoryResult.class);
    }

    // 持久化压缩一个 session 的历史
    public CompletableFuture<SessionCompactResult> compact(String sessionId, String focus) {
        return rpc.request(
                "session.compact",
                new SessionCompactCommand(sessionId, focus),
                SessionCompactResult.class);
    }

    // 列出 daemon 当前可解析的全部 skills
    public CompletableFuture<SkillListResult> listSkills() {
        return rpc.request("skill.list", new SkillListCommand(), SkillListResult.class);
    }

    // 响应一个工具权限请求
    public CompletableFuture<PermissionRespondResult> respondPermission(
            String toolUseId, String decision) {
        return rpc.request(
                "permission.respond",
                new PermissionRespondCommand(toolUseId, decision),
                PermissionRespondResult.class);
    }
}
