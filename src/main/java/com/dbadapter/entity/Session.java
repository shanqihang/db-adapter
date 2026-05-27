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

    @Column(updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    private LocalDateTime updatedAt = LocalDateTime.now();

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
