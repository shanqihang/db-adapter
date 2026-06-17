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

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;

/**
 * 对话服务 - 两阶段工作流
 *
 * 分析阶段 (analysis)：AI 只读文件、输出适配方案，捕获到的修改标记为 proposed(applied=false)
 *                      若 AI 违规修改了文件，自动回滚实际修改
 * 执行阶段 (execution)：AI 根据确认方案执行修改，捕获到的修改标记为 applied(applied=true)
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

    private final ExecutorService executorService = Executors.newCachedThreadPool(r -> {
        Thread thread = new Thread(r);
        thread.setDaemon(true);
        return thread;
    });

    private final ExecutorService startupExecutor = Executors.newCachedThreadPool(r -> {
        Thread thread = new Thread(r);
        thread.setDaemon(true);
        thread.setName("startup-validator");
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
        SseEmitter emitter = new SseEmitter(300_000L);

        if (!sessionRepo.existsById(sessionId)) {
            sendError(emitter, "会话不存在");
            return emitter;
        }

        saveMessage(sessionId, "user", userMessage);

        executorService.submit(() -> {
            try {
                if (sessionManager.isProcessing(sessionId)) {
                    sendError(emitter, "当前有消息正在处理中，请等待完成后再发送");
                    return;
                }

                Session session = sessionRepo.findById(sessionId).orElse(null);
                boolean isAnalysis = session != null && "analysis".equals(session.getStatus());

                ClaudeCliService.ClaudeSession claudeSession = sessionManager.getOrCreate(sessionId);
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

                                List<Dto.ModificationItem> mods;
                                if (isAnalysis) {
                                    mods = captureProposedModifications(sessionId, toolUseEvents, fullText.toString());
                                } else {
                                    mods = captureFileModificationsFromTools(sessionId, toolUseEvents);
                                    if (mods.isEmpty()) {
                                        mods = parseAndSaveDiffs(sessionId, fullText.toString());
                                    }
                                }

                                emitter.send(SseEmitter.event()
                                        .data(objectMapper.writeValueAsString(
                                                Dto.SseEvent.done(aiMsg.getId(), mods))));
                                emitter.complete();
                            } catch (Exception e) {
                                emitter.completeWithError(e);
                            }
                        },
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
     * 重置 claude 会话
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
                Session session = sessionRepo.findById(sessionId).orElse(null);
                boolean isAnalysis = session != null && "analysis".equals(session.getStatus());

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

                                List<Dto.ModificationItem> mods;
                                if (isAnalysis) {
                                    mods = captureProposedModifications(sessionId, toolUseEvents, fullText.toString());
                                } else {
                                    mods = captureFileModificationsFromTools(sessionId, toolUseEvents);
                                    if (mods.isEmpty()) {
                                        mods = parseAndSaveDiffs(sessionId, fullText.toString());
                                    }
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

    // ==================== 分析阶段：捕获为 proposed，回滚实际修改 ====================

    /**
     * 分析阶段：将捕获到的修改标记为 proposed（applied=false），
     * 并回滚 AI 违规实际修改的文件
     */
    private List<Dto.ModificationItem> captureProposedModifications(
            String sessionId, List<ClaudeCliService.ToolUseEvent> toolUseEvents, String fullText) {

        List<Dto.ModificationItem> result = new ArrayList<>();
        Session session = sessionRepo.findById(sessionId).orElse(null);
        String projectPath = session != null ? session.getProjectPath() : null;

        // 1. 从工具调用事件捕获
        for (ClaudeCliService.ToolUseEvent toolUse : toolUseEvents) {
            try {
                if ("Edit".equals(toolUse.name) || "Write".equals(toolUse.name)) {
                    String filePath = toolUse.input.path("file_path").asText();
                    if (filePath.isEmpty()) continue;

                    String absPath = resolveFilePath(projectPath, filePath);

                    // 回滚 AI 违规的文件修改
                    revertUnauthorizedModification(toolUse, absPath);

                    String description = "Edit".equals(toolUse.name)
                            ? "建议替换内容" : "建议创建文件";

                    String originalContent = "";
                    String modifiedContent = "";

                    if ("Edit".equals(toolUse.name)) {
                        originalContent = toolUse.input.path("old_string").asText("");
                        modifiedContent = toolUse.input.path("new_string").asText("");
                    } else if ("Write".equals(toolUse.name)) {
                        modifiedContent = toolUse.input.path("content").asText("");
                    }

                    FileDiff diff = new FileDiff();
                    diff.setSessionId(sessionId);
                    diff.setFilePath(absPath);
                    diff.setDescription(description);
                    diff.setOriginalContent(originalContent);
                    diff.setModifiedContent(modifiedContent);
                    diff.setApplied(false);   // 标记为"待确认"
                    diff.setAutoApplied(false);
                    diffRepo.save(diff);

                    Dto.ModificationItem mod = new Dto.ModificationItem();
                    mod.setFilePath(filePath);
                    mod.setDescription(description);
                    mod.setOriginal(originalContent);
                    mod.setModified(modifiedContent);
                    result.add(mod);

                    log.info("分析阶段 - 捕获修改建议: {} ({})", absPath, description);
                }
            } catch (Exception e) {
                log.warn("处理工具调用事件失败: {}", e.getMessage());
            }
        }

        // 2. 兜底：从 AI 回复的 JSON 解析
        if (result.isEmpty()) {
            result = parseAndSaveDiffsAsProposed(sessionId, fullText);
        }

        return result;
    }

    /**
     * 回滚 AI 在分析阶段违规实际修改的文件
     */
    private void revertUnauthorizedModification(ClaudeCliService.ToolUseEvent toolUse, String absPath) {
        try {
            Path path = Path.of(absPath);
            if (!Files.exists(path)) return;

            if ("Edit".equals(toolUse.name)) {
                String oldString = toolUse.input.path("old_string").asText("");
                String newString = toolUse.input.path("new_string").asText("");
                if (!oldString.isEmpty() && !newString.isEmpty()) {
                    String content = Files.readString(path, StandardCharsets.UTF_8);
                    if (content.contains(newString)) {
                        String reverted = content.replaceFirst(
                                java.util.regex.Pattern.quote(newString), oldString);
                        Files.writeString(path, reverted, StandardCharsets.UTF_8);
                        log.warn("分析阶段 - 已回滚 AI 违规修改: {}", absPath);
                    }
                }
            }
            // Write 工具创建的新文件：无法自动回滚（不知道原内容），记录警告
            if ("Write".equals(toolUse.name)) {
                log.warn("分析阶段 - AI 违规创建了文件: {}，无法自动回滚，请人工检查", absPath);
            }
        } catch (Exception e) {
            log.error("回滚违规修改失败: {}", absPath, e);
        }
    }

    /**
     * 从 AI 回复的 JSON 解析修改建议，保存为 proposed（applied=false）
     */
    private List<Dto.ModificationItem> parseAndSaveDiffsAsProposed(String sessionId, String fullText) {
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
                diff.setApplied(false);   // 待确认
                diff.setAutoApplied(false);
                diffRepo.save(diff);
                result.add(mod);
            }
            log.info("分析阶段 - 解析到 {} 处修改建议（待确认）", result.size());
        } catch (Exception e) {
            log.warn("解析修改建议 JSON 失败: {}", e.getMessage());
        }
        return result;
    }

    // ==================== 执行阶段：捕获为已应用 ====================

    /**
     * 执行阶段：从工具调用事件中捕获文件修改（Edit/Write 工具），标记为已应用
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

                    String absPath = resolveFilePath(projectPath, filePath);
                    Path path = Path.of(absPath);

                    String modifiedContent = "";
                    String originalContent = "";

                    if (Files.exists(path)) {
                        try {
                            modifiedContent = Files.readString(path, StandardCharsets.UTF_8);
                        } catch (Exception e) {
                            log.warn("读取修改后文件失败: {}", absPath);
                        }
                    }

                    String description = "Edit".equals(toolUse.name)
                            ? "Claude 编辑了文件"
                            : "Claude 创建了文件";

                    if ("Edit".equals(toolUse.name)) {
                        originalContent = toolUse.input.path("old_string").asText("");
                        String newString = toolUse.input.path("new_string").asText("");
                        if (!newString.isEmpty()) {
                            description = "替换内容";
                        }
                    }

                    // 创建备份
                    String backupPath = null;
                    if (Files.exists(path)) {
                        backupPath = absPath + ".bak." + System.currentTimeMillis();
                        Files.copy(path, Path.of(backupPath), StandardCopyOption.REPLACE_EXISTING);
                    }

                    FileDiff diff = new FileDiff();
                    diff.setSessionId(sessionId);
                    diff.setFilePath(absPath);
                    diff.setDescription(description);
                    diff.setOriginalContent(originalContent);
                    diff.setModifiedContent(modifiedContent);
                    diff.setApplied(true);
                    diff.setAutoApplied(true);
                    diff.setAppliedAt(LocalDateTime.now());
                    diff.setBackupPath(backupPath);
                    diffRepo.save(diff);

                    Dto.ModificationItem mod = new Dto.ModificationItem();
                    mod.setFilePath(filePath);
                    mod.setDescription(description);
                    mod.setOriginal(originalContent);
                    mod.setModified(modifiedContent);
                    result.add(mod);

                    log.info("执行阶段 - 捕获文件修改: {} ({})", absPath, description);
                }
            } catch (Exception e) {
                log.warn("处理工具调用事件失败: {}", e.getMessage());
            }
        }

        return result;
    }

    // ==================== Diff 解析（兜底，执行阶段用） ====================

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
                diff.setApplied(false);
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

    // ==================== 项目启动验证 ====================

    /**
     * 一键启动并验证适配项目
     * @param sessionId 会话ID
     * @param startupCommand 启动命令
     * @return SSE emitter 用于流式返回启动日志
     */
    public SseEmitter validateStartup(String sessionId, String startupCommand) {
        SseEmitter emitter = new SseEmitter(600_000L); // 10分钟超时

        if (!sessionRepo.existsById(sessionId)) {
            sendError(emitter, "会话不存在");
            return emitter;
        }

        startupExecutor.submit(() -> {
            try {
                Session session = sessionRepo.findById(sessionId).orElse(null);
                if (session == null || session.getProjectPath() == null || session.getProjectPath().isBlank()) {
                    sendError(emitter, "请先配置项目路径");
                    return;
                }

                // 保存启动验证开始消息
                saveMessage(sessionId, "system", "🚀 开始启动验证项目...\n启动命令: " + startupCommand);

                // 执行启动命令
                Process process = executeStartupCommand(sessionId, startupCommand, emitter);

                // 等待进程结束
                int exitCode = process.waitFor();

                // 发送完成事件
                if (exitCode == 0) {
                    sendSystemMessage(emitter, "✅ 项目启动成功！退出码: " + exitCode);
                    sendSystemMessage(emitter, "💡 如果需要分析启动日志或修复问题，可以将错误日志复制给AI进行分析");
                } else {
                    sendSystemMessage(emitter, "❌ 项目启动失败！退出码: " + exitCode);
                    sendSystemMessage(emitter, "💡 请将错误日志复制给AI，AI可以帮助分析问题并提供修复建议");
                }

                emitter.complete();

            } catch (Exception e) {
                log.error("启动验证异常 session={}", sessionId, e);
                sendError(emitter, "启动验证失败: " + e.getMessage());
            }
        });

        return emitter;
    }

    /**
     * 执行启动命令
     */
    private Process executeStartupCommand(String sessionId, String command, SseEmitter emitter) throws IOException, InterruptedException {
        Session session = sessionRepo.findById(sessionId).orElse(null);
        String projectPath = session != null ? session.getProjectPath() : null;

        // 解析命令
        String[] cmd;
        if (System.getProperty("os.name").toLowerCase().contains("win")) {
            cmd = new String[]{"cmd.exe", "/c", command};
        } else {
            cmd = new String[]{"sh", "-c", command};
        }

        // 创建进程
        ProcessBuilder pb = new ProcessBuilder(cmd);
        if (projectPath != null && !projectPath.isBlank()) {
            pb.directory(new File(projectPath));
        }
        pb.redirectErrorStream(true); // 合并错误流到输出流

        Process process = pb.start();

        // 创建线程读取输出流
        new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    // 实时发送日志
                    sendSystemMessage(emitter, "📄 " + line);
                }
            } catch (IOException e) {
                log.debug("读取启动进程输出流异常", e);
            }
        }).start();

        return process;
    }

    /**
     * 发送系统消息到SSE
     */
    private void sendSystemMessage(SseEmitter emitter, String message) {
        try {
            ChatMessage msg = saveMessage("", "system", message);
            emitter.send(SseEmitter.event()
                    .data(objectMapper.writeValueAsString(Dto.SseEvent.system(msg.getId(), message))));
        } catch (IOException e) {
            log.debug("发送SSE消息失败", e);
        }
    }

    // ==================== 启动日志分析 ====================

    /**
     * 分析启动日志
     * @param sessionId 会话ID
     * @param logContent 启动日志内容
     * @return SSE emitter 用于流式返回分析结果
     */
    public SseEmitter analyzeStartupLog(String sessionId, String logContent) {
        SseEmitter emitter = new SseEmitter(300_000L); // 5分钟超时

        if (!sessionRepo.existsById(sessionId)) {
            sendError(emitter, "会话不存在");
            return emitter;
        }

        executorService.submit(() -> {
            try {
                Session session = sessionRepo.findById(sessionId).orElse(null);
                if (session == null || session.getProjectPath() == null || session.getProjectPath().isBlank()) {
                    sendError(emitter, "请先配置项目路径");
                    return;
                }

                // 保存启动日志分析开始消息
                saveMessage(sessionId, "system", "🔍 开始分析启动日志...");

                // 构建日志分析提示
                String analysisPrompt = "请帮我分析以下启动日志，找出问题和解决方案。项目信息：\n" +
                        "- 数据库类型: " + (session.getDbType() != null ? session.getDbType() : "未配置") + "\n" +
                        "- 项目路径: " + session.getProjectPath() + "\n\n" +
                        "请分析以下日志内容：\n" +
                        "```\n" + logContent + "\n```\n\n" +
                        "请从以下角度分析：\n" +
                        "1. 数据库连接相关错误\n" +
                        "2. 依赖冲突或缺失\n" +
                        "3. 配置文件问题\n" +
                        "4. 代码适配问题\n" +
                        "5. 给出具体的修复建议\n\n" +
                        "请详细分析并给出可执行的修复方案。";

                // 重置claude会话并发送分析指令
                SseEmitter chatEmitter = resetAndChat(sessionId, analysisPrompt);

                // 转发聊天事件到启动日志分析的事件流
                // 这里简化处理，直接使用聊天功能，实际中可能需要更复杂的转发逻辑

                // 发送分析消息到现有的claude会话
                ClaudeCliService.ClaudeSession claudeSession = sessionManager.getOrCreate(sessionId);
                claudeSession.sendMessage(
                        analysisPrompt,
                        chunk -> {
                            try {
                                emitter.send(SseEmitter.event()
                                        .data(objectMapper.writeValueAsString(Dto.SseEvent.chunk(chunk))));
                            } catch (IOException e) {
                                log.debug("发送启动日志分析chunk失败", e);
                            }
                        },
                        toolUse -> {
                            // 处理工具调用
                            log.info("启动日志分析中的工具调用: {}", toolUse.name);
                        },
                        done -> {
                            try {
                                // 发送完成事件
                                ChatMessage aiMsg = saveMessage(sessionId, "assistant", done);
                                emitter.send(SseEmitter.event()
                                        .data(objectMapper.writeValueAsString(Dto.SseEvent.done(aiMsg.getId(), null))));
                                emitter.complete();
                            } catch (IOException e) {
                                log.debug("发送启动日志分析完成事件失败", e);
                            }
                        },
                        err -> {
                            sendError(emitter, "启动日志分析失败: " + err);
                        }
                );

            } catch (Exception e) {
                log.error("启动日志分析异常 session={}", sessionId, e);
                sendError(emitter, "启动日志分析失败: " + e.getMessage());
            }
        });

        return emitter;
    }
}
