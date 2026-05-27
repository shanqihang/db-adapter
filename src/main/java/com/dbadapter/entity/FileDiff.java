package com.dbadapter.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "file_diffs")
@Data
@NoArgsConstructor
public class FileDiff {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false)
    private String sessionId;

    /** 文件的绝对路径 */
    @Column(length = 2048, nullable = false)
    private String filePath;

    /** 修改说明 */
    @Column(length = 512)
    private String description;

    /** 修改前的内容片段 */
    @Column(columnDefinition = "TEXT")
    private String originalContent;

    /** 修改后的内容片段 */
    @Column(columnDefinition = "TEXT")
    private String modifiedContent;

    /** 是否已应用到磁盘 */
    private boolean applied = false;

    /** 是否由 AI 自动应用（true=AI 直接修改，false=待用户手动应用） */
    private boolean autoApplied = false;

    private LocalDateTime appliedAt;

    /** 备份文件路径 */
    @Column(length = 2048)
    private String backupPath;

    @Column(updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
