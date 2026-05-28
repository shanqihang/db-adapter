package com.dbadapter.service;

import com.dbadapter.entity.Session;
import org.springframework.stereotype.Component;

/**
 * 构建数据库适配的 System Prompt
 *
 * 两阶段工作流：
 *   分析阶段 (analysis)：只读取文件、输出适配方案，严禁使用 Edit/Write 工具修改任何文件
 *   执行阶段 (execution)：根据用户确认的方案，实际修改项目文件
 */
@Component
public class SkillPromptBuilder {

    // ==================== 分析阶段提示词 ====================

    /**
     * 分析阶段：强约束，禁止修改文件
     */
    public String buildAnalysisPrompt(Session session) {
        StringBuilder sb = new StringBuilder();

        sb.append("""
                # 当前模式：分析模式（ANALYSIS MODE）

                ## ⚠️ 严格规则 — 必须遵守

                你现在处于**分析模式**，这意味着：
                - ✅ 允许使用 Read、Grep、Glob 工具读取和搜索项目文件
                - ❌ **严禁使用 Edit、Write、NotebookEdit 工具修改任何文件**
                - ❌ **严禁对项目文件做任何写入操作**

                如果违反此规则直接修改文件，用户的代码将被破坏，这是绝对不可接受的。

                ## 你的任务

                1. **扫描和分析**：仔细阅读项目的 pom.xml、配置文件、Mapper XML、Java 配置类等
                2. **识别问题**：找出所有需要适配的数据库相关代码
                3. **输出适配方案**：在回复末尾以 JSON 格式列出所有需要修改的内容

                ## 输出格式

                分析完成后，必须在回复末尾附加如下 JSON 块：

                ```json
                {
                  "modifications": [
                    {
                      "filePath": "相对于项目根目录的路径，如 pom.xml 或 src/main/resources/application.yml",
                      "description": "修改说明（详细描述为什么要改、改成什么）",
                      "original": "文件中需要被替换的原始内容（必须精确匹配）",
                      "modified": "替换后的新内容"
                    }
                  ]
                }
                ```

                规则：
                - `original` 必须与文件内容**完全一致**（用于后续精确替换）
                - 一个文件可以有多条 modification
                - 如果没有需要修改的内容，不输出 JSON 块
                - 使用中文回答，代码保持原语言
                - 先给出文字分析说明，再给出 JSON 修改方案

                """);

        sb.append(buildContextPrompt(session));
        sb.append("\n");
        sb.append(getDbSpecificRules(session.getDbType()));

        return sb.toString();
    }

    // ==================== 执行阶段提示词 ====================

    /**
     * 执行阶段：根据确认的方案执行修改
     */
    public String buildExecutionPrompt(Session session, String approvedPlanSummary) {
        StringBuilder sb = new StringBuilder();

        sb.append("""
                # 当前模式：执行模式（EXECUTION MODE）

                ## 你的任务

                用户已确认了以下适配方案，请按照方案**逐项执行修改**：
                - 使用 Edit 或 Write 工具修改项目文件
                - 严格按照方案中的 original 和 modified 内容进行替换
                - 每完成一处修改，简要确认
                - 如果执行中发现方案有不合理之处，先说明问题再决定是否继续

                ## 注意事项
                - 修改前确保 original 内容与文件实际内容匹配
                - 保持代码风格与原项目一致
                - 使用中文说明，代码保持原语言

                """);

        if (approvedPlanSummary != null && !approvedPlanSummary.isBlank()) {
            sb.append("## 用户确认的适配方案\n\n");
            sb.append(approvedPlanSummary);
            sb.append("\n\n");
        }

        sb.append(buildContextPrompt(session));

        return sb.toString();
    }

    // ==================== 通用上下文（追加到 Skill 后面） ====================

    /**
     * 构建追加到 Skill 后面的上下文提示（数据库类型、地址等）
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

        return sb.toString();
    }

    // ==================== 数据库专项规则 ====================

    private String getDbDisplayName(String dbType) {
        if (dbType == null) return "";
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
}
