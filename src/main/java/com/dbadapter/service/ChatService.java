package com.dbadapter.service;

import com.dbadapter.dto.Dto;
import com.dbadapter.entity.ChatMessage;
import com.dbadapter.entity.FileDiff;
import com.dbadapter.entity.Session;
import com.dbadapter.repository.ChatMessageRepository;
import com.dbadapter.repository.FileDiffRepository;
import com.dbadapter.repository.SessionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.*;

/**
 * 对话服务 - 使用持久 claude 进程，支持真正的多轮交互
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

    private final SessionRepository sessionRepo;
    private final ChatMessageRepository messageRepo;
    private final FileDiffRepository diffRepo;
    private final ClaudeSessionManager sessionManager;
    private final ObjectMapper objectMapper;

    // 使用线程池替代虚拟线程
    private final ExecutorService executorService = Executors.newCachedThreadPool(r -> {
        Thread thread = new Thread(r);
        thread.setDaemon(true);
        return thread;
    });

    private static final Pattern MODIFICATIONS_PATTERN = Pattern.compile(
            "```json\\s*\\{\\s*\"modifications\"\\s*:[\\s\\S]*?\\}\\s*```",
            Pattern.DOTALL);

    // ==================== 对话（SSE 流式） ====================

    /**
     * 用户发消息，转发给对应的 claude 持久进程，SSE 流式推送回复
     */
    public SseEmitter handleChat(String sessionId, String userMessage) {
        SseEmitter emitter = new SseEmitter(300_000L); // 5 分钟（AI 操作文件可能较慢）

        if (!sessionRepo.existsById(sessionId)) {
            sendError(emitter, "会话不存在");
            return emitter;
        }

        // 保存用户消息
        saveMessage(sessionId, "user", userMessage);

        // 异步：获取/创建 claude 进程，发消息，流式推送
        executorService.submit(() -> {
            try {
                // 如果正在处理，拒绝新消息
                if (sessionManager.isProcessing(sessionId)) {
                    sendError(emitter, "当前有消息正在处理中，请等待完成后再发送");
                    return;
                }

                ClaudeCliService.ClaudeSession claudeSession = sessionManager.getOrCreate(sessionId);
                StringBuilder fullText = new StringBuilder();
                List<ClaudeCliService.ToolUseEvent> toolUseEvents = new ArrayList<>();

                claudeSession.sendMessage(
                        userMessage,
                        // onChunk：每收到文本片段
                        chunk -> {
                            fullText.append(chunk);
                            try {
                                emitter.send(SseEmitter.event()
                                        .data(objectMapper.writeValueAsString(Dto.SseEvent.chunk(chunk))));
                            } catch (Exception ignored) {}
                        },
                        // onToolUse：收到工具调用事件
                        toolUse -> {
                            toolUseEvents.add(toolUse);
                            log.info("捕获工具调用: {} (id={})", toolUse.name, toolUse.toolUseId);
                        },
                        // onDone：本轮回复完成
                        done -> {
                            try {
                                ChatMessage aiMsg = saveMessage(sessionId, "assistant", fullText.toString());

                                // 从工具调用事件捕获文件修改
                                List<Dto.ModificationItem> mods = captureFileModificationsFromTools(sessionId, toolUseEvents);

                                // 兜底：解析 markdown JSON 格式的修改建议
                                if (mods.isEmpty()) {
                                    mods = parseAndSaveDiffs(sessionId, fullText.toString());
                                }

                                emitter.send(SseEmitter.event()
                                        .data(objectMapper.writeValueAsString(
                                                Dto.SseEvent.done(aiMsg.getId(), mods))));
                                emitter.complete();
                            } catch (Exception e) {
                                emitter.completeWithError(e);
                            }
                        },
                        // onError
                        err -> {
                            saveMessage(sessionId, "system", "❌ " + err);
                            sendError(emitter, err);
                        }
                );

            } catch (Exception e) {
                log.error("handleChat 异常 session={}", sessionId, e);
                sendError(emitter, "启动 claude 失败: " + e.getMessage());
            }
        });

        return emitter;
    }

    /**
     * 重置 claude 会话（强制重建进程，清空 claude 的上下文记忆）
     */
    public SseEmitter resetAndChat(String sessionId, String userMessage) {
        SseEmitter emitter = new SseEmitter(300_000L);

        if (!sessionRepo.existsById(sessionId)) {
            sendError(emitter, "会话不存在");
            return emitter;
        }

        saveMessage(sessionId, "system", "🔄 claude 会话已重置（上下文已清空）");
        saveMessage(sessionId, "user", userMessage);

        executorService.submit(() -> {
            try {
                ClaudeCliService.ClaudeSession claudeSession = sessionManager.restart(sessionId);
                StringBuilder fullText = new StringBuilder();
                List<ClaudeCliService.ToolUseEvent> toolUseEvents = new ArrayList<>();

                claudeSession.sendMessage(
                        userMessage,
                        chunk -> {
                            fullText.append(chunk);
                            try {
                                emitter.send(SseEmitter.event()
                                        .data(objectMapper.writeValueAsString(Dto.SseEvent.chunk(chunk))));
                            } catch (Exception ignored) {}
                        },
                        toolUse -> {
                            toolUseEvents.add(toolUse);
                            log.info("捕获工具调用: {} (id={})", toolUse.name, toolUse.toolUseId);
                        },
                        done -> {
                            try {
                                ChatMessage aiMsg = saveMessage(sessionId, "assistant", fullText.toString());

                                // 从工具调用事件捕获文件修改
                                List<Dto.ModificationItem> mods = captureFileModificationsFromTools(sessionId, toolUseEvents);

                                // 兜底：解析 markdown JSON 格式的修改建议
                                if (mods.isEmpty()) {
                                    mods = parseAndSaveDiffs(sessionId, fullText.toString());
                                }

                                emitter.send(SseEmitter.event()
                                        .data(objectMapper.writeValueAsString(
                                                Dto.SseEvent.done(aiMsg.getId(), mods))));
                                emitter.complete();
                            } catch (Exception e) {
                                emitter.completeWithError(e);
                            }
                        },
                        err -> sendError(emitter, err)
                );
            } catch (Exception e) {
                sendError(emitter, "重置失败: " + e.getMessage());
            }
        });

        return emitter;
    }

    // ==================== 消息持久化 ====================

    @Transactional
    public ChatMessage saveMessage(String sessionId, String role, String content) {
        ChatMessage msg = new ChatMessage();
        msg.setSessionId(sessionId);
        msg.setRole(role);
        msg.setContent(content);
        return messageRepo.save(msg);
    }

    // ==================== 工具调用捕获文件修改 ====================

    /**
     * 从工具调用事件中捕获文件修改（Edit/Write 工具）
     */
    private List<Dto.ModificationItem> captureFileModificationsFromTools(
            String sessionId, List<ClaudeCliService.ToolUseEvent> toolUseEvents) {

        List<Dto.ModificationItem> result = new ArrayList<>();
        Session session = sessionRepo.findById(sessionId).orElse(null);
        String projectPath = session != null ? session.getProjectPath() : null;

        for (ClaudeCliService.ToolUseEvent toolUse : toolUseEvents) {
            try {
                if ("Edit".equals(toolUse.name) || "Write".equals(toolUse.name)) {
                    String filePath = toolUse.input.path("file_path").asText();
                    if (filePath.isEmpty()) continue;

                    // 解析绝对路径
                    String absPath = resolveFilePath(projectPath, filePath);
                    Path path = Path.of(absPath);

                    // 读取修改后的内容（claude 已经执行了工具）
                    String modifiedContent = "";
                    String originalContent = "";

                    if (Files.exists(path)) {
                        try {
                            modifiedContent = Files.readString(path, StandardCharsets.UTF_8);
                        } catch (Exception e) {
                            log.warn("读取修改后文件失败: {}", absPath);
                        }
                    }

                    // 尝试从备份读取原始内容（如果有）
                    // 注意：claude 的 Edit 工具不会自动备份，这里我们标记为"已应用"
                    String description = "Edit".equals(toolUse.name)
                            ? "Claude 编辑了文件"
                            : "Claude 创建了文件";

                    // 对于 Edit 工具，尝试从 input 中提取 old_string
                    if ("Edit".equals(toolUse.name)) {
                        originalContent = toolUse.input.path("old_string").asText("");
                        String newString = toolUse.input.path("new_string").asText("");
                        if (!newString.isEmpty()) {
                            description = "替换内容";
                        }
                    }

                    FileDiff diff = new FileDiff();
                    diff.setSessionId(sessionId);
                    diff.setFilePath(absPath);
                    diff.setDescription(description);
                    diff.setOriginalContent(originalContent);
                    diff.setModifiedContent(modifiedContent);
                    diff.setApplied(true); // claude 已直接修改
                    diff.setAutoApplied(true); // 标记为自动应用
                    diff.setAppliedAt(LocalDateTime.now());
                    diffRepo.save(diff);

                    Dto.ModificationItem mod = new Dto.ModificationItem();
                    mod.setFilePath(filePath);
                    mod.setDescription(description);
                    mod.setOriginal(originalContent);
                    mod.setModified(modifiedContent);
                    result.add(mod);

                    log.info("捕获文件修改: {} ({})", absPath, description);
                }
            } catch (Exception e) {
                log.warn("处理工具调用事件失败: {}", e.getMessage());
            }
        }

        return result;
    }

    // ==================== Diff 解析（兜底） ====================

    /**
     * 从 AI 回复中解析修改建议 JSON，存入数据库（兜底机制）
     */
    private List<Dto.ModificationItem> parseAndSaveDiffs(String sessionId, String fullText) {
        List<Dto.ModificationItem> result = new ArrayList<>();
        Matcher matcher = MODIFICATIONS_PATTERN.matcher(fullText);
        if (!matcher.find()) return result;

        String jsonBlock = matcher.group()
                .replaceAll("^```json\\s*", "")
                .replaceAll("\\s*```$", "")
                .trim();
        try {
            Dto.ModificationList modList = objectMapper.readValue(jsonBlock, Dto.ModificationList.class);
            if (modList.getModifications() == null) return result;

            Session session = sessionRepo.findById(sessionId).orElse(null);
            for (Dto.ModificationItem mod : modList.getModifications()) {
                String absPath = resolveFilePath(
                        session != null ? session.getProjectPath() : null, mod.getFilePath());
                FileDiff diff = new FileDiff();
                diff.setSessionId(sessionId);
                diff.setFilePath(absPath);
                diff.setDescription(mod.getDescription());
                diff.setOriginalContent(mod.getOriginal());
                diff.setModifiedContent(mod.getModified());
                diffRepo.save(diff);
                result.add(mod);
            }
            log.info("解析到 {} 处修改建议", result.size());
        } catch (Exception e) {
            log.warn("解析修改建议 JSON 失败: {}", e.getMessage());
        }
        return result;
    }

    private String resolveFilePath(String projectPath, String filePath) {
        if (filePath == null) return "";
        if (filePath.startsWith("/") || (filePath.length() > 1 && filePath.charAt(1) == ':')) return filePath;
        if (projectPath != null && !projectPath.isBlank()) return Path.of(projectPath, filePath).toString();
        return filePath;
    }

    // ==================== 工具方法 ====================

    private void sendError(SseEmitter emitter, String msg) {
        try {
            emitter.send(SseEmitter.event()
                    .data(objectMapper.writeValueAsString(Dto.SseEvent.error(msg))));
            emitter.complete();
        } catch (IOException e) {
            emitter.completeWithError(e);
        }
    }
}
