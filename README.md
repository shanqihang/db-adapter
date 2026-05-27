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
claude \
  --input-format stream-json \
  --output-format stream-json \
  --dangerously-skip-permissions \
  --append-system-prompt "数据库上下文"
```

**关键特性**：
- ✅ **stream-json 协议**：stdin/stdout 流式 JSON 通信，支持真正的多轮对话
- ✅ **全局 Skill 自动加载**：使用 `~/.claude/CLAUDE.md` 和 `~/.claude/skills/` 中的规则
- ✅ **工具调用捕获**：自动捕获 Claude 的 Edit/Write 工具调用，记录文件修改前后对比
- ✅ **持久进程**：每个会话对应一个常驻 claude 进程，保留上下文记忆
- ✅ **文件直接操作**：Claude 在项目目录下运行，可直接读写项目文件
- ✅ **修改可回滚**：自动备份原文件，支持一键回滚

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

### 3. 构建并启动

```bash
bash start.sh
# 或手动：
mvn package -DskipTests
java -jar target/db-adapter-assistant-1.0.0.jar
```

### 4. 访问

- 主界面：http://localhost:8080
- H2 数据库控制台：http://localhost:8080/h2-console（JDBC URL: `jdbc:h2:file:./data/db-adapter`）

## 项目结构

```
db-adapter/
├── src/main/java/com/dbadapter/
│   ├── DbAdapterApplication.java      # 启动类 + CORS 配置
│   ├── controller/ApiController.java  # REST API（会话/聊天/扫描/Diff/回滚）
│   ├── service/
│   │   ├── ClaudeCliService.java      # ★ 核心：stream-json 协议调用 claude
│   │   ├── ChatService.java           # 编排 AI 调用、存消息、捕获工具调用
│   │   ├── ClaudeSessionManager.java  # 管理 claude 持久进程生命周期
│   │   ├── SkillPromptBuilder.java    # 构建数据库上下文提示
│   │   └── FileService.java          # 项目扫描、文件读写、应用修改
│   ├── entity/                        # JPA 实体（Session / ChatMessage / FileDiff）
│   ├── repository/                    # Spring Data JPA Repositories
│   └── dto/Dto.java                   # 所有 DTO
├── src/main/resources/
│   ├── application.yml                # Spring Boot 配置
│   └── static/                        # 前端静态资源
│       ├── index.html
│       ├── css/style.css
│       └── js/app.js
├── data/                              # 运行时 H2 数据库文件（自动创建）
├── pom.xml
└── start.sh
```

## 配置说明

`application.yml` 中的关键配置：

```yaml
claude:
  cli-path: claude          # claude 命令路径（如果不在 PATH 中，写绝对路径）
  timeout-ms: 300000        # 每次调用超时（毫秒）
  max-concurrent: 3         # 最大并发 claude 进程数
  global-skill-enabled: true # 是否启用全局 Skill（~/.claude/CLAUDE.md）
```

## 全局 Skill 机制

系统默认启用 `global-skill-enabled: true`，此时：
1. Claude CLI 自动加载 `~/.claude/CLAUDE.md` 和 `~/.claude/skills/` 中的规则
2. 后端通过 `--append-system-prompt` 追加当前会话的数据库上下文（类型、地址、项目路径）
3. 不使用 `--system-prompt` 参数，避免覆盖全局 Skill

如果设置 `global-skill-enabled: false`，则只使用 `--append-system-prompt` 中的内建规则。

## 工作流程

1. **创建会话**：在前端新建会话，配置目标数据库类型、地址、项目路径
2. **扫描项目**：点击"扫描项目"，系统自动识别 pom.xml、配置文件、Mapper XML 等
3. **对话适配**：直接对话，例如"帮我检查 pom.xml 的数据库依赖是否需要修改"
4. **自动修改**：Claude 使用 Edit/Write 工具直接修改项目文件，系统自动捕获并记录
5. **查看对比**：在"修改记录" Tab 查看修改前后对比，支持一键回滚
6. **持续对话**：进程保留上下文，可继续追问"还有哪些 SQL 需要改"

## 数据存储

使用 H2 嵌入式数据库，文件存储在 `./data/db-adapter.mv.db`，包含三张表：

- `sessions`：会话（名称、数据库配置、项目路径）
- `chat_messages`：对话记录（角色、内容、时间）
- `file_diffs`：修改记录（文件路径、修改前后内容、是否已应用、是否自动应用、备份路径）

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
在 `~/.claude/CLAUDE.md` 或 `~/.claude/skills/` 中添加规则，系统会自动加载。也可以在 `SkillPromptBuilder.java` 的 `getDbSpecificRules()` 方法中添加内建规则作为兜底。

**Q: Claude 修改了文件但我想撤销怎么办？**
在"修改记录" Tab 中找到对应的修改，点击"回滚"按钮即可从备份恢复。

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
3. 如果 Claude 只输出了 JSON 建议而未实际修改，可在"修改记录"中手动应用

## 技术亮点

- **stream-json 协议**：正确实现 Claude CLI 的流式通信，支持工具调用捕获
- **持久进程**：每个会话一个常驻进程，保留完整上下文，避免重复初始化
- **全局 Skill**：利用 Claude Code 的 Skill 系统，规则可复用、可版本管理
- **自动捕获**：监听 tool_use 事件，无需 AI 输出特定格式的 JSON
- **安全回滚**：自动备份原文件，支持一键恢复
- **零配置存储**：H2 嵌入式数据库，无需额外安装
