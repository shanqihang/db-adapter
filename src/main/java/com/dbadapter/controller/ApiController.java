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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.*;

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
        if (req.getMessage() == null || req.getMessage().isBlank()) return errorEmitter("消息不能为空");
        return chatService.handleChat(id, req.getMessage());
    }

    /** 重置对话：关闭旧进程，新开 claude 进程，再发第一条消息 */
    @PostMapping(value = "/sessions/{id}/reset-chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter resetChat(@PathVariable String id, @RequestBody Dto.ChatReq req) {
        if (!sessionRepo.existsById(id)) return errorEmitter("会话不存在");
        String msg = req.getMessage() != null && !req.getMessage().isBlank()
                ? req.getMessage() : "你好，请准备开始适配工作";
        return chatService.resetAndChat(id, msg);
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
