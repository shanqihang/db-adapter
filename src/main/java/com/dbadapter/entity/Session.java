package com.dbadapter.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "sessions")
@Data
@NoArgsConstructor
public class Session {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false)
    private String name;

    /** 目标数据库类型：dameng / kingbase / gaussdb / tidb / oscar */
    private String dbType;

    private String dbHost;
    private String dbPort;
    private String dbName;

    /** Java 项目的本地绝对路径 */
    @Column(length = 1024)
    private String projectPath;

    /**
     * 会话阶段/状态：
     * analysis  - 分析阶段：AI 扫描项目并提出适配方案（不修改文件）
     * review    - 方案评审：用户查看/调整方案
     * execution - 执行阶段：应用确认的修改
     * completed - 已完成
     * terminated- 已终止（回滚所有修改）
     */
    @Column(length = 20, nullable = false, columnDefinition = "varchar(20) default 'analysis'")
    private String status = "analysis";

    @Column(updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    private LocalDateTime updatedAt = LocalDateTime.now();

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
