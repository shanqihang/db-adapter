package com.dbadapter.controller;

import com.dbadapter.dto.Dto;
import com.dbadapter.entity.ChatMessage;
import com.dbadapter.entity.FileDiff;
import com.dbadapter.entity.Session;
import com.dbadapter.repository.ChatMessageRepository;
import com.dbadapter.repository.FileDiffRepository;
import com.dbadapter.repository.SessionRepository;
import com.dbadapter.service.ChatService;
import com.dbadapter.service.ClaudeCliService;
import com.dbadapter.service.ClaudeSessionManager;
import com.dbadapter.service.FileService;
import com.dbadapter.service.SkillInstaller;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Stream;

@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ApiController {

    private final SessionRepository sessionRepo;
    private final ChatMessageRepository messageRepo;
    private final FileDiffRepository diffRepo;
    private final ChatService chatService;
    private final ClaudeCliService claudeCliService;
    private final ClaudeSessionManager sessionManager;
    private final FileService fileService;
    private final SkillInstaller skillInstaller;

    // ==================== 系统状态 ====================

    @GetMapping("/status")
    public ResponseEntity<?> status() {
        boolean cliOk = claudeCliService.isCliAvailable();
        return ResponseEntity.ok(Map.of(
                "ok", true,
                "claudeCliAvailable", cliOk,
                "resolvedCliPath", claudeCliService.getResolvedCliPath(),
                "activeProcesses", claudeCliService.getActiveProcessCount(),
                "message", cliOk ? "Claude CLI 可用" : "Claude CLI 不可用，请检查安装"
        ));
    }

    /**
     * 健康检查：返回 skill 安装状态
     *
     * <p>暴露 {@link SkillInstaller#getInstallDir()} 以及该目录下已安装的 skill 列表，
     * 方便排查"为什么 claude 没加载到 db-adapter skill"之类的问题。
     */
    @GetMapping("/health")
    public ResponseEntity<?> health() {
        Path installDir = skillInstaller.getInstallDir();
        boolean dirExists = Files.isDirectory(installDir);

        List<Map<String, Object>> skills = new ArrayList<>();
        if (dirExists) {
            try (Stream<Path> children = Files.list(installDir)) {
                children.filter(Files::isDirectory)
                        .sorted()
                        .forEach(skillDir -> skills.add(describeSkill(skillDir)));
            } catch (Exception e) {
                log.warn("枚举 skill 目录失败: {}", e.getMessage());
            }
        }

        // db-adapter skill 是否就绪（核心检查项）
        Path dbAdapterSkill = installDir.resolve("db-adapter").resolve("SKILL.md");
        boolean dbAdapterReady = Files.isRegularFile(dbAdapterSkill);

        boolean cliOk = claudeCliService.isCliAvailable();
        boolean overallOk = cliOk && dbAdapterReady;

        return ResponseEntity.ok(Map.of(
                "ok", overallOk,
                "claudeCliAvailable", cliOk,
                "skillsInstallDir", installDir.toString(),
                "skillsInstallDirExists", dirExists,
                "dbAdapterSkillReady", dbAdapterReady,
                "skills", skills,
                "message", overallOk
                        ? "所有组件就绪"
                        : (cliOk ? "db-adapter skill 未就绪，请检查启动日志" : "Claude CLI 不可用")
        ));
    }

    /** 描述单个 skill 目录的安装状态 */
    private Map<String, Object> describeSkill(Path skillDir) {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("name", skillDir.getFileName().toString());
        info.put("path", skillDir.toString());

        Path skillFile = skillDir.resolve("SKILL.md");
        boolean exists = Files.isRegularFile(skillFile);
        info.put("skillFileExists", exists);

        if (exists) {
            try {
                BasicFileAttributes attrs = Files.readAttributes(skillFile, BasicFileAttributes.class);
                info.put("sizeBytes", attrs.size());
                info.put("lastModified", LocalDateTime.ofInstant(
                        attrs.lastModifiedTime().toInstant(), ZoneId.systemDefault()).toString());
            } catch (Exception e) {
                info.put("error", "读取文件属性失败: " + e.getMessage());
            }
        }

        return info;
    }

    // ==================== 会话管理 ====================

    @GetMapping("/sessions")
    public ResponseEntity<?> listSessions() {
        return ResponseEntity.ok(
                sessionRepo.findAllByOrderByCreatedAtDesc().stream().map(Dto.SessionResp::from).toList());
    }

    @PostMapping("/sessions")
    public ResponseEntity<?> createSession(@RequestBody Dto.SessionCreateReq req) {
        if (req.getName() == null || req.getName().isBlank())
            return ResponseEntity.badRequest().body(new Dto.ErrResp("会话名称不能为空"));
        Session s = new Session();
        s.setName(req.getName().trim());
        s.setDbType(req.getDbType());
        s.setDbHost(req.getDbHost());
        s.setDbPort(req.getDbPort());
        s.setDbName(req.getDbName());
        s.setProjectPath(req.getProjectPath());
        sessionRepo.save(s);
        return ResponseEntity.ok(Dto.SessionResp.from(s));
    }

    @GetMapping("/sessions/{id}")
    public ResponseEntity<?> getSession(@PathVariable String id) {
        return sessionRepo.findById(id)
                .map(s -> ResponseEntity.ok(Dto.SessionResp.from(s)))
                .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/sessions/{id}")
    public ResponseEntity<?> updateSession(@PathVariable String id,
                                            @RequestBody Dto.SessionUpdateReq req) {
        return sessionRepo.findById(id).map(s -> {
            if (req.getName() != null) s.setName(req.getName());
            if (req.getDbType() != null) s.setDbType(req.getDbType());
            if (req.getDbHost() != null) s.setDbHost(req.getDbHost());
            if (req.getDbPort() != null) s.setDbPort(req.getDbPort());
            if (req.getDbName() != null) s.setDbName(req.getDbName());
            if (req.getProjectPath() != null) s.setProjectPath(req.getProjectPath());
            sessionRepo.save(s);
            // 配置变化时关闭旧 claude 进程，下次发消息用新配置重建
            sessionManager.closeSession(id);
            return ResponseEntity.ok(Dto.SessionResp.from(s));
        }).orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/sessions/{id}")
    @Transactional
    public ResponseEntity<?> deleteSession(@PathVariable String id) {
        if (!sessionRepo.existsById(id)) return ResponseEntity.notFound().build();
        sessionManager.closeSession(id);
        messageRepo.deleteBySessionId(id);
        diffRepo.deleteBySessionId(id);
        sessionRepo.deleteById(id);
        return ResponseEntity.ok(new Dto.OkResp("已删除"));
    }

    // ==================== claude 进程状态 ====================

    @GetMapping("/sessions/{id}/process-status")
    public ResponseEntity<?> processStatus(@PathVariable String id) {
        return ResponseEntity.ok(Map.of(
                "sessionId", id,
                "alive", sessionManager.isAlive(id),
                "processing", sessionManager.isProcessing(id)
        ));
    }

    /** 强制关闭并重建 claude 进程（清空 claude 的上下文记忆） */
    @PostMapping("/sessions/{id}/restart-process")
    public ResponseEntity<?> restartProcess(@PathVariable String id) {
        if (!sessionRepo.existsById(id)) return ResponseEntity.notFound().build();
        sessionManager.closeSession(id);
        chatService.saveMessage(id, "system", "🔄 claude 进程已重置，下次发消息将重新启动");
        return ResponseEntity.ok(new Dto.OkResp("已重置 claude 进程"));
    }

    /** 重置会话：清空上下文和消息记录，等待下次对话 */
    @PostMapping("/sessions/{id}/reset")
    @Transactional
    public ResponseEntity<?> resetSession(@PathVariable String id) {
        if (!sessionRepo.existsById(id)) return ResponseEntity.notFound().build();
        Session s = sessionRepo.findById(id).get();
        if ("terminated".equals(s.getStatus()) || "completed".equals(s.getStatus()))
            return ResponseEntity.badRequest().body(new Dto.ErrResp("该会话已结束，无法重置"));

        sessionManager.closeSession(id);
        messageRepo.deleteBySessionId(id);
        diffRepo.deleteBySessionId(id);
        s.setStatus("idle");
        sessionRepo.save(s);
        log.info("会话 {} 已重置，清空了消息和修改记录", id);
        return ResponseEntity.ok(Map.of("ok", true, "message", "会话已重置，上下文已清空"));
    }

    /** 终止会话：关闭 claude 进程 + 回滚本轮所有已应用的修改 */
    @PostMapping("/sessions/{id}/terminate")
    @Transactional
    public ResponseEntity<?> terminateSession(@PathVariable String id) {
        return sessionRepo.findById(id).map(s -> {
            if ("terminated".equals(s.getStatus()))
                return ResponseEntity.badRequest().body(new Dto.ErrResp("该会话已终止"));

            // 1. 关闭 claude 进程
            sessionManager.closeSession(id);

            // 2. 回滚所有已应用的 FileDiff
            List<FileDiff> appliedDiffs = diffRepo.findBySessionIdAndAppliedTrueOrderByCreatedAtDesc(id);
            int rolledBack = 0;
            int failed = 0;
            for (FileDiff diff : appliedDiffs) {
                if (diff.getBackupPath() != null && !diff.getBackupPath().isEmpty()) {
                    try {
                        Path backupPath = Path.of(diff.getBackupPath());
                        Path targetPath = Path.of(diff.getFilePath());
                        if (Files.exists(backupPath)) {
                            Files.copy(backupPath, targetPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                            diff.setApplied(false);
                            diff.setAppliedAt(null);
                            diffRepo.save(diff);
                            rolledBack++;
                        } else {
                            failed++;
                            log.warn("终止会话回滚：备份文件不存在: {}", diff.getBackupPath());
                        }
                    } catch (Exception e) {
                        failed++;
                        log.error("终止会话回滚文件失败: {}", diff.getFilePath(), e);
                    }
                } else {
                    // 无备份的自动应用修改（claude 直接编辑的），尝试用 originalContent 恢复
                    if (diff.isAutoApplied() && diff.getOriginalContent() != null
                            && !diff.getOriginalContent().isEmpty()) {
                        try {
                            Path targetPath = Path.of(diff.getFilePath());
                            if (Files.exists(targetPath)) {
                                String current = Files.readString(targetPath, java.nio.charset.StandardCharsets.UTF_8);
                                // 对于 Edit 工具，originalContent 是 old_string，替换回去
                                if (diff.getModifiedContent() != null && current.contains(diff.getModifiedContent())) {
                                    String restored = current.replaceFirst(
                                            java.util.regex.Pattern.quote(diff.getModifiedContent()),
                                            diff.getOriginalContent());
                                    Files.writeString(targetPath, restored, java.nio.charset.StandardCharsets.UTF_8);
                                }
                            }
                            diff.setApplied(false);
                            diff.setAppliedAt(null);
                            diffRepo.save(diff);
                            rolledBack++;
                        } catch (Exception e) {
                            failed++;
                            log.error("终止会话回滚（无备份）文件失败: {}", diff.getFilePath(), e);
                        }
                    } else {
                        failed++;
                    }
                }
            }

            // 3. 更新会话状态
            s.setStatus("terminated");
            sessionRepo.save(s);

            // 4. 记录系统消息
            String msg = String.format("⛔ 会话已终止。回滚了 %d 个文件修改%s",
                    rolledBack, failed > 0 ? "，" + failed + " 个回滚失败" : "");
            chatService.saveMessage(id, "system", msg);

            return ResponseEntity.ok(Map.of(
                    "ok", true,
                    "rolledBack", rolledBack,
                    "failed", failed,
                    "message", msg
            ));
        }).orElse(ResponseEntity.notFound().build());
    }

    // ==================== 消息 ====================

    @GetMapping("/sessions/{id}/messages")
    public ResponseEntity<?> getMessages(@PathVariable String id) {
        return ResponseEntity.ok(messageRepo.findBySessionIdOrderByCreatedAtAsc(id));
    }

    // ==================== 流式对话（SSE）====================

    /** 普通对话：消息追加到现有 claude 进程（保留上下文） */
    @PostMapping(value = "/sessions/{id}/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@PathVariable String id, @RequestBody Dto.ChatReq req) {
        if (!sessionRepo.existsById(id)) return errorEmitter("会话不存在");
        Session s = sessionRepo.findById(id).get();
        if ("terminated".equals(s.getStatus())) return errorEmitter("该会话已终止，无法继续对话");
        if ("completed".equals(s.getStatus())) return errorEmitter("该会话已完成，无法继续对话");
        if (req.getMessage() == null || req.getMessage().isBlank()) return errorEmitter("消息不能为空");
        return chatService.handleChat(id, req.getMessage());
    }

    /** 重置对话：关闭旧进程，新开 claude 进程，再发第一条消息（仅用于 start-analysis 等需要自动发送消息的场景） */
    @PostMapping(value = "/sessions/{id}/reset-chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter resetChat(@PathVariable String id, @RequestBody Dto.ChatReq req) {
        if (!sessionRepo.existsById(id)) return errorEmitter("会话不存在");
        Session s = sessionRepo.findById(id).get();
        if ("terminated".equals(s.getStatus())) return errorEmitter("该会话已终止，无法继续对话");
        if ("completed".equals(s.getStatus())) return errorEmitter("该会话已完成，无法继续对话");
        String msg = req.getMessage() != null && !req.getMessage().isBlank()
                ? req.getMessage() : "你好，请准备开始适配工作";
        return chatService.resetAndChat(id, msg);
    }

    // ==================== 两阶段工作流 ====================

    /** 开始分析：切换到 analysis 阶段，启动 claude 进程并发送分析指令 */
    @PostMapping(value = "/sessions/{id}/start-analysis", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter startAnalysis(@PathVariable String id) {
        return sessionRepo.findById(id).map(s -> {
            if ("terminated".equals(s.getStatus()) || "completed".equals(s.getStatus()))
                return errorEmitter("该会话已" + s.getStatus() + "，无法开始分析");
            if (s.getProjectPath() == null || s.getProjectPath().isBlank())
                return errorEmitter("请先在配置中设置项目路径");

            // 切换到分析阶段
            s.setStatus("analysis");
            sessionRepo.save(s);
            // 关闭旧进程，下次 getOrCreate 会用分析阶段的 prompt 重建
            sessionManager.closeSession(id);

            String analysisMsg = "请全面分析这个 Java 项目的数据库适配需求。" +
                    "扫描 pom.xml、配置文件、Mapper XML、Java 配置类等，" +
                    "找出所有需要修改的地方，给出详细的适配方案。" +
                    "注意：你现在处于分析模式，只能读取文件，不能修改任何文件。" +
                    "请将修改建议以 JSON modifications 格式输出。";

            return chatService.resetAndChat(id, analysisMsg);
        }).orElse(errorEmitter("会话不存在"));
    }

    /** 确认方案：从 analysis/review 阶段进入 execution 阶段 */
    @PostMapping("/sessions/{id}/confirm-plan")
    public ResponseEntity<?> confirmPlan(@PathVariable String id) {
        return sessionRepo.findById(id).map(s -> {
            if (!"analysis".equals(s.getStatus()) && !"review".equals(s.getStatus()))
                return ResponseEntity.badRequest().body(new Dto.ErrResp("当前阶段无法确认方案"));

            // 检查是否有待执行的 diff
            List<FileDiff> pendingDiffs = diffRepo.findBySessionIdOrderByCreatedAtAsc(id).stream()
                    .filter(d -> !d.isApplied()).toList();
            if (pendingDiffs.isEmpty())
                return ResponseEntity.badRequest().body(new Dto.ErrResp("没有待确认的修改方案"));

            // 切换到执行阶段
            s.setStatus("execution");
            sessionRepo.save(s);
            // 关闭旧进程，下次会以执行阶段的 prompt 重建
            sessionManager.closeSession(id);
            chatService.saveMessage(id, "system",
                    "✅ 方案已确认，进入执行阶段。共 " + pendingDiffs.size() + " 项修改待执行。");

            return ResponseEntity.ok(Map.of(
                    "ok", true,
                    "phase", "execution",
                    "pendingCount", pendingDiffs.size()
            ));
        }).orElse(ResponseEntity.notFound().build());
    }

    /** 批量应用所有待确认的 diff（执行阶段的核心操作） */
    @PostMapping("/sessions/{id}/apply-all-diffs")
    @Transactional
    public ResponseEntity<?> applyAllDiffs(@PathVariable String id) {
        return sessionRepo.findById(id).map(s -> {
            if (!"execution".equals(s.getStatus()) && !"review".equals(s.getStatus()))
                return ResponseEntity.badRequest().body(new Dto.ErrResp("当前阶段无法执行修改"));

            List<FileDiff> pendingDiffs = diffRepo.findBySessionIdOrderByCreatedAtAsc(id).stream()
                    .filter(d -> !d.isApplied()).toList();

            int applied = 0;
            int failed = 0;
            List<String> errors = new ArrayList<>();

            for (FileDiff diff : pendingDiffs) {
                FileService.ApplyResult result = fileService.applyModification(
                        diff.getFilePath(), diff.getOriginalContent(), diff.getModifiedContent());
                if (result.success()) {
                    diff.setApplied(true);
                    diff.setAppliedAt(LocalDateTime.now());
                    diff.setBackupPath(result.backupPath());
                    diffRepo.save(diff);
                    applied++;
                } else {
                    failed++;
                    errors.add(diff.getFilePath() + ": " + result.error());
                    log.warn("应用 diff 失败: {} - {}", diff.getFilePath(), result.error());
                }
            }

            // 全部应用完成，切换到 completed 阶段
            if (failed == 0) {
                s.setStatus("completed");
                sessionRepo.save(s);
                chatService.saveMessage(id, "system",
                        String.format("✅ 所有 %d 项修改已成功应用！", applied));
            } else {
                chatService.saveMessage(id, "system",
                        String.format("⚠️ %d 项修改已应用，%d 项失败。失败项：\n%s",
                                applied, failed, String.join("\n", errors)));
            }

            return ResponseEntity.ok(Map.of(
                    "ok", true,
                    "applied", applied,
                    "failed", failed,
                    "errors", errors,
                    "phase", s.getStatus()
            ));
        }).orElse(ResponseEntity.notFound().build());
    }

    /** 进入方案评审阶段 */
    @PostMapping("/sessions/{id}/enter-review")
    public ResponseEntity<?> enterReview(@PathVariable String id) {
        return sessionRepo.findById(id).map(s -> {
            if (!"analysis".equals(s.getStatus()))
                return ResponseEntity.badRequest().body(new Dto.ErrResp("只有分析阶段可以进入评审"));
            s.setStatus("review");
            sessionRepo.save(s);
            chatService.saveMessage(id, "system", "📋 进入方案评审阶段，请查看修改记录确认或调整方案。");
            return ResponseEntity.ok(Map.of("ok", true, "phase", "review"));
        }).orElse(ResponseEntity.notFound().build());
    }

    /** 删除某个待确认的 diff（评审阶段拒绝某项修改） */
    @DeleteMapping("/sessions/{id}/diffs/{diffId}/reject")
    public ResponseEntity<?> rejectDiff(@PathVariable String id, @PathVariable String diffId) {
        return sessionRepo.findById(id).map(s -> {
            if (!"review".equals(s.getStatus()) && !"analysis".equals(s.getStatus()))
                return ResponseEntity.badRequest().body(new Dto.ErrResp("当前阶段无法拒绝修改"));
            return diffRepo.findById(diffId).map(diff -> {
                if (diff.isApplied())
                    return ResponseEntity.badRequest().body(new Dto.ErrResp("已应用的修改无法拒绝，请使用回滚"));
                diffRepo.deleteById(diffId);
                return ResponseEntity.ok(Map.of("ok", true, "message", "已拒绝该修改"));
            }).orElse(ResponseEntity.notFound().build());
        }).orElse(ResponseEntity.notFound().build());
    }

    // ==================== 文件扫描 ====================

    @PostMapping("/sessions/{id}/scan")
    public ResponseEntity<?> scanProject(@PathVariable String id) {
        return sessionRepo.findById(id).map(s -> {
            if (s.getProjectPath() == null || s.getProjectPath().isBlank())
                return ResponseEntity.badRequest().body(new Dto.ErrResp("请先配置项目路径"));
            Dto.ScanResult result = fileService.scanProject(s.getProjectPath());
            if (!result.isExists())
                return ResponseEntity.badRequest().body(new Dto.ErrResp(result.getError()));
            String summary = String.format(
                    "📁 项目扫描完成：pom.xml %d个，配置文件 %d个，Mapper XML %d个，Java配置类 %d个",
                    result.getSummary().getPomCount(), result.getSummary().getConfigCount(),
                    result.getSummary().getMapperCount(), result.getSummary().getJavaConfigCount());
            chatService.saveMessage(id, "system", summary);
            return ResponseEntity.ok(result);
        }).orElse(ResponseEntity.notFound().build());
    }

    // ==================== Diff 管理 ====================

    @GetMapping("/sessions/{id}/diffs")
    public ResponseEntity<?> getDiffs(@PathVariable String id) {
        return ResponseEntity.ok(
                diffRepo.findBySessionIdOrderByCreatedAtAsc(id).stream()
                        .map(Dto.FileDiffResp::from).toList());
    }

    @PostMapping("/diffs/{diffId}/apply")
    @Transactional
    public ResponseEntity<?> applyDiff(@PathVariable String diffId) {
        return diffRepo.findById(diffId).map(diff -> {
            if (diff.isApplied())
                return ResponseEntity.badRequest().body(new Dto.ErrResp("该修改已应用"));
            FileService.ApplyResult result = fileService.applyModification(
                    diff.getFilePath(), diff.getOriginalContent(), diff.getModifiedContent());
            if (result.success()) {
                diff.setApplied(true);
                diff.setAppliedAt(LocalDateTime.now());
                diff.setBackupPath(result.backupPath());
                diffRepo.save(diff);
                return ResponseEntity.ok(Map.of("ok", true, "backupPath", result.backupPath()));
            }
            return ResponseEntity.badRequest().body(new Dto.ErrResp(result.error()));
        }).orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/diffs/{diffId}")
    public ResponseEntity<?> deleteDiff(@PathVariable String diffId) {
        if (!diffRepo.existsById(diffId)) return ResponseEntity.notFound().build();
        diffRepo.deleteById(diffId);
        return ResponseEntity.ok(new Dto.OkResp());
    }

    @PostMapping("/diffs/{diffId}/rollback")
    @Transactional
    public ResponseEntity<?> rollbackDiff(@PathVariable String diffId) {
        return diffRepo.findById(diffId).map(diff -> {
            if (!diff.isApplied())
                return ResponseEntity.badRequest().body(new Dto.ErrResp("该修改未应用，无需回滚"));
            if (diff.getBackupPath() == null || diff.getBackupPath().isEmpty())
                return ResponseEntity.badRequest().body(new Dto.ErrResp("无备份文件，无法回滚"));

            try {
                // 从备份恢复文件
                Path backupPath = Path.of(diff.getBackupPath());
                Path targetPath = Path.of(diff.getFilePath());
                if (!Files.exists(backupPath))
                    return ResponseEntity.badRequest().body(new Dto.ErrResp("备份文件不存在: " + diff.getBackupPath()));

                Files.copy(backupPath, targetPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);

                // 更新状态
                diff.setApplied(false);
                diff.setAppliedAt(null);
                diffRepo.save(diff);

                return ResponseEntity.ok(Map.of("ok", true, "message", "已从备份恢复"));
            } catch (Exception e) {
                return ResponseEntity.badRequest().body(new Dto.ErrResp("回滚失败: " + e.getMessage()));
            }
        }).orElse(ResponseEntity.notFound().build());
    }

    // ==================== 项目启动验证 ====================

    /** 一键启动验证项目 */
    @PostMapping(value = "/sessions/{id}/validate-startup", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter validateStartup(@PathVariable String id, @RequestBody Dto.StartupReq req) {
        if (!sessionRepo.existsById(id)) return errorEmitter("会话不存在");

        Session s = sessionRepo.findById(id).orElse(null);
        if (s == null) return errorEmitter("会话不存在");
        if ("terminated".equals(s.getStatus())) return errorEmitter("该会话已终止");
        if ("completed".equals(s.getStatus())) return errorEmitter("该会话已完成");
        if (req.getStartupCommand() == null || req.getStartupCommand().isBlank())
            return errorEmitter("启动命令不能为空");

        return chatService.validateStartup(id, req.getStartupCommand());
    }

    /** 分析启动日志 */
    @PostMapping(value = "/sessions/{id}/analyze-startup-log", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter analyzeStartupLog(@PathVariable String id, @RequestBody Dto.StartupLogReq req) {
        if (!sessionRepo.existsById(id)) return errorEmitter("会话不存在");

        Session s = sessionRepo.findById(id).orElse(null);
        if (s == null) return errorEmitter("会话不存在");
        if ("terminated".equals(s.getStatus())) return errorEmitter("该会话已终止");
        if ("completed".equals(s.getStatus())) return errorEmitter("该会话已完成");
        if (req.getLogContent() == null || req.getLogContent().isBlank())
            return errorEmitter("日志内容不能为空");

        return chatService.analyzeStartupLog(id, req.getLogContent());
    }

    // ==================== 文件读取 ====================

    @PostMapping("/read-file")
    public ResponseEntity<?> readFile(@RequestBody Map<String, String> body) {
        String filePath = body.get("filePath");
        if (filePath == null || filePath.isBlank())
            return ResponseEntity.badRequest().body(new Dto.ErrResp("filePath 不能为空"));
        try {
            return ResponseEntity.ok(Map.of("content", fileService.readFile(filePath), "filePath", filePath));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(new Dto.ErrResp("读取失败: " + e.getMessage()));
        }
    }

    // ==================== 工具方法 ====================

    private SseEmitter errorEmitter(String msg) {
        SseEmitter e = new SseEmitter();
        try {
            e.send(SseEmitter.event().data("{\"type\":\"error\",\"message\":\"" + msg + "\"}"));
            e.complete();
        } catch (Exception ex) { e.completeWithError(ex); }
        return e;
    }
}
