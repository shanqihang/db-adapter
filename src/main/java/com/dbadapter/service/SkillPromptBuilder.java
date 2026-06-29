package com.dbadapter.service;

import com.dbadapter.entity.Session;
import org.springframework.stereotype.Component;

/**
 * 构建数据库适配的 System Prompt
 *
 * <p>权威知识来源：<code>~/.claude/skills/db-adapter/SKILL.md</code>
 * （由 {@link SkillInstaller} 在启动时从 classpath 安装到用户全局目录）。
 *
 * <p>本类只负责注入两类信息：
 * <ul>
 *   <li>当前会话所处的模式（CHAT / ANALYSIS / EXECUTION）以及该模式下的强制约束</li>
 *   <li>当前会话的上下文（目标数据库类型、地址、项目路径）</li>
 * </ul>
 * 具体的适配规则（驱动、URL、SQL 语法对照等）由 db-adapter skill 提供。
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

    private String dbDisplayName(String dbType) {
        if (dbType == null) return null;
        return DB_DISPLAY_NAMES.getOrDefault(dbType, dbType);
    }

    // ==================== 空闲/对话模式 ====================

    public String buildIdlePrompt(Session session) {
        StringBuilder sb = new StringBuilder();

        sb.append("""
                # 当前模式：对话模式（CHAT MODE）

                ## 你的角色

                你是一个数据库适配助手，正在帮助用户将 Java 项目从 MySQL 适配到国产数据库。
                你可以自由地和用户对话，回答问题，提供建议。

                如需进行实际的适配分析或执行，请加载 **db-adapter** skill 获取完整适配规则。

                ## 规则

                - 你可以读取项目文件来了解上下文
                - 你可以回答用户关于数据库适配的问题
                - 不要主动修改项目文件，只有在用户明确要求时才操作
                - 使用中文回答，代码保持原语言

                """);

        sb.append(buildContextPrompt(session));

        return sb.toString();
    }

    // ==================== 分析模式 ====================

    public String buildAnalysisPrompt(Session session) {
        StringBuilder sb = new StringBuilder();

        sb.append("""
                # 当前模式：分析模式（ANALYSIS MODE）

                ## 先决条件

                请先加载 **db-adapter** skill（位于 `~/.claude/skills/db-adapter/SKILL.md`），
                其中包含 12 种国产数据库的完整适配规则、JDBC URL 格式、SQL 语法对照等知识库。

                ## ⚠️ 严格规则 — 必须遵守

                你现在处于**分析模式**，这意味着：
                - ✅ 允许使用 Read、Grep、Glob 工具读取和搜索项目文件
                - ❌ **严禁使用 Edit、Write、NotebookEdit 工具修改任何文件**
                - ❌ **严禁对项目文件做任何写入操作**

                如果违反此规则直接修改文件，用户的代码将被破坏，这是绝对不可接受的。
                系统会自动回滚违规修改，并在日志中告警。

                ## 你的任务

                按照 db-adapter skill 中的"阶段 1：分析模式"工作流执行：

                1. **扫描和分析**：仔细阅读项目的 pom.xml、配置文件、Mapper XML、Java 配置类等
                2. **识别问题**：找出所有需要适配的数据库相关代码
                3. **输出适配方案**：在回复末尾以 JSON 格式列出所有需要修改的内容

                ## 输出格式（必须严格遵守 — 后端解析器依赖此格式）

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

        return sb.toString();
    }

    // ==================== 执行模式 ====================

    public String buildExecutionPrompt(Session session, String approvedPlanSummary) {
        StringBuilder sb = new StringBuilder();

        sb.append("""
                # 当前模式：执行模式（EXECUTION MODE）

                ## 先决条件

                请先加载 **db-adapter** skill（位于 `~/.claude/skills/db-adapter/SKILL.md`）以参考适配规则细节。

                ## 你的任务

                用户已确认了适配方案，请按照 db-adapter skill 中的"阶段 2：执行模式"工作流**逐项执行修改**：
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

    // ==================== 会话上下文 ====================

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
