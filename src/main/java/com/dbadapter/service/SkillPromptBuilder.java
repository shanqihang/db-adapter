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
        sb.append("## 适配规则\n\n");
        sb.append("请使用你掌握的数据库适配技能（init-db-adapt Skill），针对目标数据库类型进行适配。\n");

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
            sb.append("- **目标数据库类型**: ").append(session.getDbType()).append("\n");
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
