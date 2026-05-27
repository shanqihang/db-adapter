package com.dbadapter.service;

import com.dbadapter.entity.Session;
import com.dbadapter.repository.SessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Claude 进程会话管理器
 *
 * 每个应用会话（Session）对应一个 claude CLI 持久进程。
 * 进程在第一次发消息时启动，空闲超时后自动关闭（节省资源）。
 *
 * 设计原则：
 *   - 一个 sessionId → 一个 ClaudeCliService.ClaudeSession（进程）
 *   - 进程工作目录 = session.projectPath
 *   - 进程使用 claude 全局 Skill，只追加数据库类型上下文
 *   - 30 分钟无活动自动关闭，下次发消息自动重建
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ClaudeSessionManager {

    private final ClaudeCliService claudeCliService;
    private final SessionRepository sessionRepository;
    private final SkillPromptBuilder promptBuilder;

    /** sessionId → claude 进程会话 */
    private final Map<String, ClaudeCliService.ClaudeSession> activeSessions = new ConcurrentHashMap<>();
    /** sessionId → 最后活跃时间戳 */
    private final Map<String, Long> lastActiveTime = new ConcurrentHashMap<>();

    /** 空闲超时：30 分钟 */
    private static final long IDLE_TIMEOUT_MS = 30 * 60 * 1000L;

    /**
     * 获取或创建指定 sessionId 对应的 claude 进程会话。
     * 如果进程已关闭或不存在，自动重建。
     */
    public ClaudeCliService.ClaudeSession getOrCreate(String sessionId) throws Exception {
        ClaudeCliService.ClaudeSession existing = activeSessions.get(sessionId);
        if (existing != null && existing.isAlive()) {
            lastActiveTime.put(sessionId, System.currentTimeMillis());
            return existing;
        }

        // 进程不存在或已死，清理后重建
        if (existing != null) {
            log.info("会话 {} 的 claude 进程已退出，重新启动", sessionId);
            closeSession(sessionId);
        }

        return createNew(sessionId);
    }

    /**
     * 强制重建 claude 进程（用于「重置会话」操作）
     */
    public ClaudeCliService.ClaudeSession restart(String sessionId) throws Exception {
        closeSession(sessionId);
        return createNew(sessionId);
    }

    /**
     * 关闭指定会话的 claude 进程
     */
    public void closeSession(String sessionId) {
        ClaudeCliService.ClaudeSession session = activeSessions.remove(sessionId);
        lastActiveTime.remove(sessionId);
        if (session != null) {
            try { session.close(); } catch (Exception e) {
                log.debug("关闭会话 {} 时出错: {}", sessionId, e.getMessage());
            }
        }
    }

    /**
     * 查询 claude 进程是否存活
     */
    public boolean isAlive(String sessionId) {
        ClaudeCliService.ClaudeSession s = activeSessions.get(sessionId);
        return s != null && s.isAlive();
    }

    /**
     * 查询 claude 进程是否正在处理消息
     */
    public boolean isProcessing(String sessionId) {
        ClaudeCliService.ClaudeSession s = activeSessions.get(sessionId);
        return s != null && s.isProcessing();
    }

    public Map<String, Long> getLastActiveTimes() {
        return Map.copyOf(lastActiveTime);
    }

    // ==================== 私有方法 ====================

    private ClaudeCliService.ClaudeSession createNew(String sessionId) throws Exception {
        Session appSession = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("会话不存在: " + sessionId));

        // 只追加数据库类型/地址等上下文，全局 Skill 由 claude 自动加载
        String appendPrompt = promptBuilder.buildContextPrompt(appSession);

        ClaudeCliService.ClaudeSession claudeSession = claudeCliService.startSession(
                appSession.getProjectPath(), appendPrompt);

        activeSessions.put(sessionId, claudeSession);
        lastActiveTime.put(sessionId, System.currentTimeMillis());
        log.info("会话 {} 的 claude 进程已启动 (pid={})", sessionId, claudeSession.getPid());

        return claudeSession;
    }

    /**
     * 定时清理空闲超时的 claude 进程（每 5 分钟检查一次）
     */
    @Scheduled(fixedDelay = 5 * 60 * 1000)
    public void cleanupIdleSessions() {
        long now = System.currentTimeMillis();
        lastActiveTime.forEach((sessionId, lastActive) -> {
            if (now - lastActive > IDLE_TIMEOUT_MS) {
                log.info("会话 {} 空闲超时，关闭 claude 进程", sessionId);
                closeSession(sessionId);
            }
        });
    }
}
