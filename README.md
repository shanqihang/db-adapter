# 国产数据库适配助手 - Java Edition

> Spring Boot 3.x 后端 + 本地 Claude Code CLI 驱动的国产数据库适配工具

## 架构说明

```
浏览器前端 (HTML/CSS/JS)
       ↓  REST API / SSE 流式
Spring Boot 3.x 后端
       ↓  ProcessBuilder (stream-json 协议)
claude --input-format stream-json --output-format stream-json   ← 本地 Claude Code CLI
       ↓  Anthropic API
Claude 模型（通过 claude CLI 的认证）
```

**核心调用命令**：
```bash
# 分析阶段（禁止修改文件）
claude \
  --input-format stream-json \
  --output-format stream-json \
  --dangerously-skip-permissions \
  --append-system-prompt "ANALYSIS MODE: 只读文件，输出适配方案，严禁使用 Edit/Write 工具..."

# 执行阶段（根据确认方案修改文件）
claude \
  --input-format stream-json \
  --output-format stream-json \
  --dangerously-skip-permissions \
  --append-system-prompt "EXECUTION MODE: 根据用户确认的方案执行修改..."
```

**关键特性**：
- ✅ **两阶段工作流**：分析阶段只读出方案 → 用户确认 → 执行阶段修改文件
- ✅ **全局 Skill 自动加载**：使用 `~/.claude/CLAUDE.md` 和 `~/.claude/skills/` 中的规则（如 `init-db`）
- ✅ **stream-json 协议**：stdin/stdout 流式 JSON 通信，支持真正的多轮对话
- ✅ **工具调用捕获**：自动捕获 Claude 的 Edit/Write 工具调用，记录文件修改前后对比
- ✅ **持久进程**：每个会话对应一个常驻 claude 进程，保留上下文记忆
- ✅ **修改可回滚**：自动备份原文件，支持逐条回滚和终止会话一键回滚全部修改
- ✅ **违规修改自动回滚**：分析阶段若 AI 违规修改文件，自动检测并恢复

## 环境要求

| 依赖 | 版本 | 说明 |
|------|------|------|
| Java | 17+ | Spring Boot 3.x 要求 |
| Maven | 3.8+ | 构建工具 |
| Claude Code CLI | 最新版 | `npm i -g @anthropic-ai/claude-code` |
| Node.js | 18+ | claude CLI 运行时 |

## 快速启动

### 1. 安装 Claude Code CLI 并登录

```bash
npm install -g @anthropic-ai/claude-code
claude auth login           # 用 Claude.ai 账号认证
# 或使用 API Key：
claude auth login --console  # 用 Anthropic Console API Key 认证
```

### 2. 配置全局 Skill（可选）

在 `~/.claude/CLAUDE.md` 或 `~/.claude/skills/` 目录下放置你的数据库适配规则。系统会自动加载这些规则。

示例 `~/.claude/CLAUDE.md`：
```markdown
# 国产数据库适配专家

你是一名专业的国产数据库适配专家...

## 达梦 DM8 适配规则
- JDBC URL: jdbc:dm://host:5236/DB
- Driver: dm.jdbc.driver.DmDriver
...
```

也可以将 `init-db` 等 Skill 放入 `~/.claude/skills/init-db/` 目录，Claude 会自动识别。

### 3. 构建并启动

```bash
bash start.sh
# 或手动：
mvn package -DskipTests
java -jar target/db-adapter-assistant-1.0.0.jar
```

### 4. 访问

- 主界面： http://localhost:8080
- H2 数据库控制台：http://localhost:8080/h2-console（JDBC URL: `jdbc:h2:file:./data/db-adapter`）

## 项目结构

```
db-adapter/
├── src/main/java/com/dbadapter/
│   ├── DbAdapterApplication.java      # 启动类 + CORS 配置
│   ├── controller/ApiController.java  # REST API（会话/聊天/扫描/Diff/工作流/回滚）
│   ├── controller/GlobalExceptionHandler.java  # 全局异常处理
│   ├── service/
│   │   ├── ClaudeCliService.java      # ★ 核心：stream-json 协议调用 claude
│   │   ├── ChatService.java           # 编排 AI 调用、存消息、区分分析/执行模式
│   │   ├── ClaudeSessionManager.java  # 管理 claude 持久进程生命周期
│   │   ├── SkillPromptBuilder.java    # ★ 两阶段提示词：分析模式 + 执行模式
│   │   └── FileService.java           # 项目扫描、文件读写、应用修改
│   ├── entity/                        # JPA 实体（Session / ChatMessage / FileDiff）
│   ├── repository/                    # Spring Data JPA Repositories
│   └── dto/Dto.java                   # 所有 DTO
├── src/main/resources/
│   ├── application.yml                # Spring Boot 配置
│   └── static/                        # 前端静态资源
│       ├── index.html                 # 主页面（含工作流按钮和阶段徽标）
│       ├── css/style.css              # 样式（含工作流/阶段相关样式）
│       └── js/app.js                  # 前端逻辑（含两阶段工作流控制）
├── data/                              # 运行时 H2 数据库文件（自动创建）
├── pom.xml
└── start.sh
```

## 两阶段工作流

这是本系统的核心设计，确保用户在确认方案前，项目文件不会被修改。

### 工作流阶段

```
analysis(分析) ──→ review(评审) ──→ execution(执行) ──→ completed(完成)
    │                  │                  │
    └──────────────────┴──────────────────┘
                       ↓
                terminated(终止，回滚所有修改)
```

### 各阶段说明

| 阶段 | AI 行为 | 用户操作 | 文件修改 |
|------|---------|---------|----------|
| **analysis** | 只读项目文件，输出适配方案，严禁修改文件 | 点击"开始分析"、自由对话 | 无（方案标记为"待确认"） |
| **review** | 可对话调整方案 | 逐条确认/拒绝修改建议 | 无（用户控制） |
| **execution** | 根据确认方案执行修改 | 批量应用或逐条应用修改 | 应用到文件（自动备份） |
| **completed** | 无 | 查看记录 | 只读 |
| **terminated** | 无 | 查看记录 | 全部回滚 |

### 安全机制

1. **分析阶段强约束**：系统提示词明确禁止 AI 使用 Edit/Write 工具修改文件
2. **违规自动回滚**：若 AI 在分析阶段违反约束修改了文件，后端自动检测并恢复原内容
3. **执行前需确认**：所有修改方案必须经用户确认后才进入执行阶段
4. **终止全部回滚**：终止会话时一键回滚所有已应用的文件修改
5. **逐条备份**：执行阶段每次修改都创建 `.bak` 备份文件

### 操作流程

1. **创建会话** → 配置目标数据库类型、地址、项目路径
2. **点击"开始分析"** → AI 扫描项目，输出适配方案（不修改文件）
3. **查看"修改记录"** → 逐条审查修改建议，确认或拒绝
4. **点击"确认方案"** → 进入执行阶段
5. **点击"执行修改"** → 批量应用修改到项目文件（或逐条应用）
6. **随时可终止** → 点击"终止会话"回滚所有修改

## 配置说明

`application.yml` 中的关键配置：

```yaml
claude:
  cli-path: claude          # claude 命令路径（如果不在 PATH 中，写绝对路径）
  timeout-ms: 300000        # 每次调用超时（毫秒）
  max-concurrent: 3         # 最大并发 claude 进程数
  global-skill-enabled: true # 是否启用全局 Skill（~/.claude/CLAUDE.md）
```

## 支持的数据库

| 数据库 | JDBC URL | Driver | MyBatis-Plus dbType |
|--------|----------|--------|---------------------|
| 达梦 DM8 | `jdbc:dm://host:5236/DB` | `dm.jdbc.driver.DmDriver` | `dm` |
| 人大金仓 KingbaseES | `jdbc:kingbase8://host:54321/DB` | `com.kingbase8.Driver` | `kingbase_es` |
| 华为 GaussDB | `jdbc:gaussdb://host:8000/DB` | `com.huawei.gaussdb.jdbc.Driver` | PostgreSQL 兼容 |
| TiDB | `jdbc:mysql://host:4000/DB` | `com.mysql.cj.jdbc.Driver` | MySQL 兼容 |
| 神通 Oscar | `jdbc:oscar://host:2003/DB` | `com.oscar.Driver` | - |

## 全局 Skill 机制

系统默认启用 `global-skill-enabled: true`，此时：
1. Claude CLI 自动加载 `~/.claude/CLAUDE.md` 和 `~/.claude/skills/` 中的规则
2. 后端通过 `--append-system-prompt` 追加当前会话的数据库上下文（类型、地址、项目路径）
3. 不使用 `--system-prompt` 参数，避免覆盖全局 Skill
4. 分析阶段和执行阶段使用不同的追加提示词

如果设置 `global-skill-enabled: false`，则只使用 `--append-system-prompt` 中的内建规则。

## API 接口

### 系统状态
- `GET /api/status` — 系统状态（Claude CLI 可用性、进程数）

### 会话管理
- `GET /api/sessions` — 会话列表
- `POST /api/sessions` — 创建会话
- `GET /api/sessions/{id}` — 获取会话
- `PUT /api/sessions/{id}` — 更新会话配置
- `DELETE /api/sessions/{id}` — 删除会话（含所有消息和 Diff）

### 两阶段工作流
- `POST /api/sessions/{id}/start-analysis` — 开始分析（SSE 流式）
- `POST /api/sessions/{id}/enter-review` — 进入评审阶段
- `POST /api/sessions/{id}/confirm-plan` — 确认方案，进入执行阶段
- `POST /api/sessions/{id}/apply-all-diffs` — 批量应用所有待执行的修改
- `POST /api/sessions/{id}/terminate` — 终止会话并回滚所有修改

### 对话
- `POST /api/sessions/{id}/chat` — 流式对话（SSE）
- `POST /api/sessions/{id}/reset-chat` — 重置对话（SSE）
- `GET /api/sessions/{id}/messages` — 获取历史消息

### Diff 管理
- `GET /api/sessions/{id}/diffs` — 获取修改记录
- `POST /api/diffs/{diffId}/apply` — 应用单个修改
- `POST /api/diffs/{diffId}/rollback` — 回滚单个修改
- `DELETE /api/sessions/{id}/diffs/{diffId}/reject` — 拒绝修改建议
- `DELETE /api/diffs/{diffId}` — 删除修改记录

### 其他
- `POST /api/sessions/{id}/scan` — 扫描项目文件
- `POST /api/read-file` — 读取文件内容
- `GET /api/sessions/{id}/process-status` — Claude 进程状态

## 数据存储

使用 H2 嵌入式数据库，文件存储在 `./data/db-adapter.mv.db`，包含三张表：

- `sessions`：会话（名称、数据库配置、项目路径、阶段状态）
- `chat_messages`：对话记录（角色、内容、时间）
- `file_diffs`：修改记录（文件路径、修改前后内容、是否已应用、是否自动应用、备份路径）

### Session 阶段状态

| 状态 | 说明 |
|------|------|
| `analysis` | 分析阶段，AI 只读文件并输出方案 |
| `review` | 评审阶段，用户审查方案 |
| `execution` | 执行阶段，应用确认的修改 |
| `completed` | 已完成，所有修改已应用 |
| `terminated` | 已终止，所有修改已回滚 |

## 常见问题

**Q: claude 命令找不到？**
```bash
# 查找 claude 安装路径
which claude || find /usr /home -name claude -type f 2>/dev/null
# 在 application.yml 中设置绝对路径
claude.cli-path: /usr/local/bin/claude
```

**Q: 没有 Anthropic API Key 能用吗？**
使用 `claude auth login` 通过 Claude.ai 账号认证即可，不需要单独的 API Key。

**Q: 如何在 Windows 上运行？**
```yaml
# application.yml 中修改：
claude:
  cli-path: C:/Users/yourname/AppData/Roaming/npm/claude.cmd
```

**Q: 如何添加更多数据库的适配规则？**
- 方式一：在 `~/.claude/CLAUDE.md` 或 `~/.claude/skills/` 中添加全局 Skill 规则
- 方式二：在 `SkillPromptBuilder.java` 的 `getDbSpecificRules()` 方法中添加内建规则

**Q: 分析阶段 AI 会不会直接修改文件？**
系统通过三层机制保护：
1. 系统提示词强约束禁止 AI 使用 Edit/Write 工具
2. 若 AI 违规修改，后端自动检测 Edit/Write 工具调用并回滚文件
3. 修改建议保存为"待确认"状态，不会自动应用

**Q: 终止会话后修改能恢复吗？**
终止会话时会自动回滚所有已应用的修改。有备份文件的从备份恢复，无备份的（AI 直接编辑的）通过 originalContent 反向替换恢复。建议在执行阶段使用批量应用而非让 AI 直接修改，以确保有完整备份。

**Q: 如何查看修改前后的对比？**
在"修改记录" Tab 中，每条修改以左右分栏形式展示修改前（左）和修改后（右）的内容，支持展开/折叠。

**Q: stream-json 协议是什么？**
这是 Claude CLI 的标准输入输出协议，stdin 写入 JSON 格式的消息，stdout 输出流式 JSON 事件。相比交互式 TUI 模式，stream-json 更适合程序化调用。

## 故障排查

### Claude CLI 不可用
1. 检查安装：`claude --version`
2. 检查认证：`claude auth status`
3. 查看日志：应用启动时会输出 claude 路径探测结果

### 进程启动失败
1. 检查项目路径是否存在且可访问
2. 检查 claude 是否有权限读写项目目录
3. 查看后端日志中的 `[claude stderr]` 输出

### 文件修改未捕获
1. 确认 Claude 使用了 Edit/Write 工具（查看对话内容）
2. 检查后端日志中的"捕获工具调用"日志
3. 如果 AI 只输出了 JSON 建议而未实际修改，修改建议会自动保存到"修改记录"

### 分析阶段 AI 仍然修改了文件
1. 检查后端日志中的"分析阶段 - 已回滚 AI 违规修改"日志
2. 系统会自动恢复被修改的文件
3. 如果自动回滚失败，请手动从备份恢复

## 技术亮点

- **两阶段工作流**：分析/执行分离，确保用户确认前文件不被修改
- **违规修改自动回滚**：分析阶段检测到 AI 违规修改文件时自动恢复
- **stream-json 协议**：正确实现 Claude CLI 的流式通信，支持工具调用捕获
- **持久进程**：每个会话一个常驻进程，保留完整上下文，避免重复初始化
- **全局 Skill**：利用 Claude Code 的 Skill 系统，规则可复用、可版本管理
- **自动捕获**：监听 tool_use 事件，无需 AI 输出特定格式的 JSON
- **安全回滚**：自动备份原文件，支持逐条回滚和终止会话一键全部回滚
- **零配置存储**：H2 嵌入式数据库，无需额外安装
