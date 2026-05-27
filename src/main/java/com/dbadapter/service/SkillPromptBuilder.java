package com.dbadapter.service;

import com.dbadapter.entity.Session;
import org.springframework.stereotype.Component;

/**
 * 构建数据库适配的 System Prompt（追加到 Skill 文件之后）
 *
 * 注意：主要的 Skill 规则通过 --system-prompt-file 加载（内网私有 Skill）。
 *       此处仅追加当前会话的上下文信息（数据库类型、地址、项目路径等）。
 *
 * 如果内网暂时没有 Skill 文件，此处会提供内建的基础规则兜底。
 */
@Component
public class SkillPromptBuilder {

    /**
     * 构建追加到 Skill 后面的上下文提示
     */
    public String buildContextPrompt(Session session) {
        StringBuilder sb = new StringBuilder();

        sb.append("## 当前适配任务上下文\n\n");

        if (session.getDbType() != null) {
            sb.append("- **目标数据库**: ").append(getDbDisplayName(session.getDbType())).append("\n");
        }
        if (session.getDbHost() != null) {
            sb.append("- **数据库地址**: ").append(session.getDbHost());
            if (session.getDbPort() != null) sb.append(":").append(session.getDbPort());
            sb.append("\n");
        }
        if (session.getDbName() != null) {
            sb.append("- **数据库名**: ").append(session.getDbName()).append("\n");
        }
        if (session.getProjectPath() != null) {
            sb.append("- **项目路径**: ").append(session.getProjectPath()).append("\n");
        }

        sb.append("\n");
        sb.append(getDbSpecificRules(session.getDbType()));
        sb.append("\n");
        sb.append(getOutputFormatRules());

        return sb.toString();
    }

    /**
     * 完整的兜底系统提示（当没有外部 Skill 文件时使用）
     * 通过 --system-prompt 替换默认提示
     */
    public String buildFullSystemPrompt(Session session) {
        StringBuilder sb = new StringBuilder();

        sb.append("""
                你是一名专业的国产数据库适配专家，专门帮助 Java 项目从 MySQL/Oracle/PostgreSQL 等数据库迁移到国产数据库。
                
                ## 核心职责
                1. 分析 Java 项目的 pom.xml、配置文件、Mapper XML、Java 配置类
                2. 识别需要修改的内容，给出精确的修改建议
                3. 以规范的 JSON 格式输出修改方案，方便系统自动处理
                4. 保持代码风格与原项目一致
                
                ## 通用适配规则
                
                ### Maven 依赖
                根据目标数据库替换 JDBC 驱动 dependency。
                
                ### 连接配置
                修改 spring.datasource.url、driver-class-name、方言(dialect)。
                
                ### MyBatis-Plus
                修改 MybatisPlusProperties 中的 dbType，调整 PageHelper 插件配置。
                
                ### SQL 兼容性
                扫描 Mapper XML，修正：DATE_FORMAT → TO_CHAR，IFNULL → NVL/COALESCE，
                GROUP_CONCAT → WM_CONCAT/LISTAGG，LIMIT/OFFSET 语法，AUTO_INCREMENT 策略。
                
                """);

        sb.append(buildContextPrompt(session));

        return sb.toString();
    }

    private String getDbDisplayName(String dbType) {
        return switch (dbType) {
            case "dameng" -> "达梦 DM8";
            case "kingbase" -> "人大金仓 KingbaseES";
            case "gaussdb" -> "华为 GaussDB";
            case "tidb" -> "TiDB";
            case "oscar" -> "神通 Oscar";
            case "mysql" -> "MySQL";
            default -> dbType;
        };
    }

    private String getDbSpecificRules(String dbType) {
        if (dbType == null) return "";
        return switch (dbType) {
            case "dameng" -> """
                    ## 达梦 DM8 专项适配规则
                    
                    **JDBC 依赖**
                    ```xml
                    <dependency>
                        <groupId>com.dameng</groupId>
                        <artifactId>DmJdbcDriver18</artifactId>
                        <version>8.1.2.192</version>
                    </dependency>
                    ```
                    **连接 URL**: `jdbc:dm://HOST:5236/SCHEMA`
                    **Driver Class**: `dm.jdbc.driver.DmDriver`
                    **Hibernate Dialect**: `org.hibernate.dialect.DmDialect`（需引入达梦方言包）
                    **MyBatis-Plus dbType**: `dm`
                    
                    **SQL 转换规则**
                    - `IFNULL(a,b)` → `NVL(a,b)` 或 `COALESCE(a,b)`
                    - `DATE_FORMAT(d,'%Y-%m-%d')` → `TO_CHAR(d,'YYYY-MM-DD')`
                    - `DATE_FORMAT(d,'%Y-%m-%d %H:%i:%s')` → `TO_CHAR(d,'YYYY-MM-DD HH24:MI:SS')`
                    - `NOW()` → `SYSDATE` 或 `CURRENT_TIMESTAMP`
                    - `GROUP_CONCAT(x)` → `WM_CONCAT(x)` 或 `LISTAGG(x,',') WITHIN GROUP (ORDER BY 1)`
                    - `AUTO_INCREMENT` → `IDENTITY(1,1)` 或使用序列
                    - 避免使用 DM 保留字做字段名：LEVEL, VALUE, KEY, COMMENT 等需加双引号
                    - `LIMIT n OFFSET m` → DM8 支持，但需确认版本（部分版本用 `ROWNUM`）
                    - 字符串拼接 `CONCAT` 可用，也支持 `||`
                    """;
            case "kingbase" -> """
                    ## 人大金仓 KingbaseES 专项适配规则
                    
                    **JDBC 依赖**
                    ```xml
                    <dependency>
                        <groupId>com.kingbase8</groupId>
                        <artifactId>kingbase8</artifactId>
                        <version>8.6.0</version>
                    </dependency>
                    ```
                    **连接 URL**: `jdbc:kingbase8://HOST:54321/DBNAME`
                    **Driver Class**: `com.kingbase8.Driver`
                    **Hibernate Dialect**: `com.kingbase8.hibernate.dialect.KingbaseESDialect`
                    **MyBatis-Plus dbType**: `kingbase_es`
                    
                    **SQL 转换规则（兼容 PostgreSQL）**
                    - `IFNULL(a,b)` → `COALESCE(a,b)`
                    - `DATE_FORMAT(d,'%Y-%m-%d')` → `TO_CHAR(d,'YYYY-MM-DD')`
                    - `NOW()` → `CURRENT_TIMESTAMP` 或 `NOW()`（KES 支持）
                    - `AUTO_INCREMENT` → `SERIAL` 或 `BIGSERIAL` 或 `GENERATED ALWAYS AS IDENTITY`
                    - `LIMIT n OFFSET m` → 支持（PostgreSQL 语法）
                    - 双引号为标识符引用，单引号为字符串
                    - `GROUP_CONCAT` → `STRING_AGG(x, ',')`
                    """;
            case "gaussdb" -> """
                    ## 华为 GaussDB 专项适配规则
                    
                    **JDBC 依赖**
                    ```xml
                    <dependency>
                        <groupId>com.huawei.gauss</groupId>
                        <artifactId>gaussdb-jdbc</artifactId>
                        <version>8.1.0</version>
                    </dependency>
                    ```
                    **连接 URL**: `jdbc:gaussdb://HOST:8000/DBNAME`
                    **Driver Class**: `com.huawei.gaussdb.jdbc.Driver`
                    **兼容模式**: 兼容 PostgreSQL，可使用 PostgreSQL Dialect
                    
                    **SQL 转换规则**
                    - `IFNULL(a,b)` → `COALESCE(a,b)` 或 `NVL(a,b)`
                    - `DATE_FORMAT` → `TO_CHAR`
                    - `AUTO_INCREMENT` → `BIGSERIAL` 或 SEQUENCE
                    - `LIMIT/OFFSET` 支持
                    - `GROUP_CONCAT` → `STRING_AGG`
                    """;
            case "tidb" -> """
                    ## TiDB 专项适配规则
                    
                    TiDB 高度兼容 MySQL，使用 MySQL JDBC 驱动即可。
                    **连接 URL**: `jdbc:mysql://HOST:4000/DBNAME`
                    **注意事项**
                    - 不支持 FOREIGN KEY（忽略即可）
                    - AUTO_INCREMENT 推荐改为 AUTO_RANDOM（分布式唯一ID）
                    - 不支持存储过程和触发器
                    - 事务隔离级别默认为 REPEATABLE-READ
                    - 大事务需拆分（默认限制 100MB）
                    """;
            default -> "";
        };
    }

    private String getOutputFormatRules() {
        return """
                ## 输出格式规范
                
                在正常回答之后，**如果有代码修改建议**，必须在回复末尾附加如下 JSON 块：
                
                ```json
                {
                  "modifications": [
                    {
                      "filePath": "相对于项目根目录的路径，如 pom.xml 或 src/main/resources/application.yml",
                      "description": "修改说明（一句话）",
                      "original": "需要被替换的原始内容（必须是文件中实际存在的精确字符串）",
                      "modified": "替换后的内容"
                    }
                  ]
                }
                ```
                
                - `original` 必须与文件内容完全匹配（用于字符串替换）
                - 一个文件可以有多条 modification 条目
                - 如果没有代码修改，不输出 JSON 块
                - 使用中文回答，代码保持原语言
                """;
    }
}
