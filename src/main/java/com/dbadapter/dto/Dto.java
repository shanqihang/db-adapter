package com.dbadapter.dto;

import com.dbadapter.entity.FileDiff;
import com.dbadapter.entity.Session;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 所有 DTO 统一放在此文件中，便于管理
 */
public class Dto {

    // ========== Session ==========

    @Data
    public static class SessionCreateReq {
        private String name;
        private String dbType;
        private String dbHost;
        private String dbPort;
        private String dbName;
        private String projectPath;
    }

    @Data
    public static class SessionUpdateReq {
        private String name;
        private String dbType;
        private String dbHost;
        private String dbPort;
        private String dbName;
        private String projectPath;
    }

    @Data
    public static class SessionResp {
        private String id;
        private String name;
        private String dbType;
        private String dbHost;
        private String dbPort;
        private String dbName;
        private String projectPath;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;

        public static SessionResp from(Session s) {
            SessionResp r = new SessionResp();
            r.id = s.getId();
            r.name = s.getName();
            r.dbType = s.getDbType();
            r.dbHost = s.getDbHost();
            r.dbPort = s.getDbPort();
            r.dbName = s.getDbName();
            r.projectPath = s.getProjectPath();
            r.createdAt = s.getCreatedAt();
            r.updatedAt = s.getUpdatedAt();
            return r;
        }
    }

    // ========== Chat ==========

    @Data
    public static class ChatReq {
        private String message;
    }

    // SSE 事件类型
    @Data
    public static class SseEvent {
        private String type;  // chunk | done | error | system
        private String text;
        private String message;
        private String messageId;
        private List<ModificationItem> modifications;

        public static SseEvent chunk(String text) {
            SseEvent e = new SseEvent();
            e.type = "chunk";
            e.text = text;
            return e;
        }

        public static SseEvent done(String messageId, List<ModificationItem> mods) {
            SseEvent e = new SseEvent();
            e.type = "done";
            e.messageId = messageId;
            e.modifications = mods;
            return e;
        }

        public static SseEvent error(String msg) {
            SseEvent e = new SseEvent();
            e.type = "error";
            e.message = msg;
            return e;
        }

        public static SseEvent system(String text) {
            SseEvent e = new SseEvent();
            e.type = "system";
            e.text = text;
            return e;
        }
    }

    // ========== Modification ==========

    @Data
    public static class ModificationItem {
        private String filePath;
        private String description;
        private String original;
        private String modified;
    }

    @Data
    public static class ModificationList {
        private List<ModificationItem> modifications;
    }

    // ========== FileDiff ==========

    @Data
    public static class FileDiffResp {
        private String id;
        private String sessionId;
        private String filePath;
        private String description;
        private String originalContent;
        private String modifiedContent;
        private boolean applied;
        private boolean autoApplied;
        private LocalDateTime appliedAt;
        private String backupPath;
        private LocalDateTime createdAt;

        public static FileDiffResp from(FileDiff d) {
            FileDiffResp r = new FileDiffResp();
            r.id = d.getId();
            r.sessionId = d.getSessionId();
            r.filePath = d.getFilePath();
            r.description = d.getDescription();
            r.originalContent = d.getOriginalContent();
            r.modifiedContent = d.getModifiedContent();
            r.applied = d.isApplied();
            r.autoApplied = d.isAutoApplied();
            r.appliedAt = d.getAppliedAt();
            r.backupPath = d.getBackupPath();
            r.createdAt = d.getCreatedAt();
            return r;
        }
    }

    // ========== Scan ==========

    @Data
    public static class ScanResult {
        private String projectPath;
        private boolean exists;
        private String error;
        private ScanSummary summary;
        private ScanFiles files;

        @Data
        public static class ScanSummary {
            private int pomCount;
            private int configCount;
            private int mapperCount;
            private int javaConfigCount;
        }

        @Data
        public static class ScanFiles {
            private List<String> pom;
            private List<String> config;
            private List<String> mapper;
            private List<String> java;
        }
    }

    // ========== Common ==========

    @Data
    public static class OkResp {
        private boolean ok = true;
        private String message;

        public OkResp() {}
        public OkResp(String message) { this.message = message; }
    }

    @Data
    public static class ErrResp {
        private String error;

        public ErrResp(String error) { this.error = error; }
    }
}
