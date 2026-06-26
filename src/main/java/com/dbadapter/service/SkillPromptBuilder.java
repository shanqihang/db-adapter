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

    /** 数据库类型标识符 → 中文展示名 映射 */
    private static final java.util.Map<String, String> DB_DISPLAY_NAMES = java.util.Map.ofEntries(
            java.util.Map.entry("mysql", "MySQL"),
            java.util.Map.entry("dm", "达梦 DM8"),
            java.util.Map.entry("kingbase_v8r6", "金仓 V8R6"),
            java.util.Map.entry("kingbase_v8r7", "金仓 V8R7"),
            java.util.Map.entry("kingbase_v9", "金仓 V9"),
            java.util.Map.entry("shentong", "神通"),
            java.util.Map.entry("highgo", "瀚高 HighGo"),
            java.util.Map.entry("vastbase", "海量 Vastbase"),
            java.util.Map.entry("youxuan", "优炫"),
            java.util.Map.entry("gbase_pg", "南大通用PG (GBase 8c)"),
            java.util.Map.entry("xugu", "虚谷"),
            java.util.Map.entry("yashandb", "崖山 YashanDB")
    );

    /** 获取数据库类型的中文展示名（找不到时回退为原始标识符） */
    private String dbDisplayName(String dbType) {
        if (dbType == null) return null;
        return DB_DISPLAY_NAMES.getOrDefault(dbType, dbType);
    }

    // ==================== 空闲模式提示词 ====================

    /**
     * 空闲/普通对话模式：自由对话，不限制工具使用
     */
    public String buildIdlePrompt(Session session) {
        StringBuilder sb = new StringBuilder();

        sb.append("""
                # 当前模式：对话模式（CHAT MODE）

                ## 你的角色

                你是一个数据库适配助手，正在帮助用户将 Java 项目从 MySQL 适配到国产数据库。
                你可以自由地和用户对话，回答问题，提供建议。

                ## 规则

                - 你可以读取项目文件来了解上下文
                - 你可以回答用户关于数据库适配的问题
                - 不要主动修改项目文件，只有在用户明确要求时才操作
                - 使用中文回答，代码保持原语言

                """);

        sb.append(buildContextPrompt(session));

        return sb.toString();
    }

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
        sb.append("## 适配规则\n\n");
        sb.append("请使用 init-db-adapt Skill 中掌握的数据库适配知识，针对目标数据库类型进行适配。\n");

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

                用户已确认了适配方案，请按照方案**逐项执行修改**：
                - 使用 Edit 或 Write 工具修改项目文件
                - 严格按照方案中的 original 和 modified 内容进行替换
                - 每完成一处修改，简要确认
                - 如果执行中发现方案有不合理之处，先说明问题再决定是否继续

                ## 注意事项
                - 修改前确保 original 内容与文件实际内容匹配
                - 保持代码风格与原项目一致
                - 使用中文说明，代码保持原语言
                - 每次只修改一处，确认成功后再修改下一处

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
            String displayName = dbDisplayName(session.getDbType());
            sb.append("- **目标数据库**: ").append(displayName);
            if (!displayName.equals(session.getDbType())) {
                sb.append(" (").append(session.getDbType()).append(")");
            }
            sb.append("\n");
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

}
