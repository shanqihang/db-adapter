# 改造日志

## 2026-05-25 完整改造 + Windows 兼容性修复

### 改造目标
将项目从原有的不完整实现改造为完全可用的国产数据库适配助手，支持：
- 前端页面配置数据库类型、地址、项目路径
- 对话记录持久化存储
- 文件修改前后对比记录
- 启动 Claude CLI 完成自动化操作
- 使用 Claude 全局 Skill
- 对话式交互

### 核心改造内容

#### 1. 修复 ClaudeCliService 改用 stream-json 协议 ✅

**问题**：原代码启动 claude 时缺少关键参数，导致进程进入交互式 TUI 模式无法通信。

**改造**：
- 添加 `--input-format stream-json` 和 `--output-format stream-json` 参数
- 添加 `--verbose` 和 `--print` 参数（stream-json 模式必需）
- 重写 `waitForInit()` 解析 `{"type":"system","subtype":"init"}` 事件
- 重写 `sendMessage()` 支持 stdin 写入 JSON 格式：`{"type":"user","message":{"role":"user","content":"..."}}`
- 重写响应读取逻辑，按行解析 JSON 事件（assistant/tool_use/tool_result/result）
- 添加 `ToolUseEvent` 类封装工具调用事件

**Windows 兼容性修复**：
- 检测 `.cmd`/`.bat` 文件，自动通过 `cmd.exe /c` 包装启动
- Java 17+ 安全限制下，ProcessBuilder 不能直接执行 .cmd 文件，必须通过 shell 包装

**文件**：
- `src/main/java/com/dbadapter/service/ClaudeCliService.java`

#### 2. 实现 tool_use 事件捕获文件修改 ✅

**问题**：原代码只能解析 AI 回复中的 markdown JSON 块，无法捕获 Claude 直接修改文件的操作。

**改造**：
- 在 `ChatService.handleChat()` 和 `resetAndChat()` 中添加 `onToolUse` 回调
- 新增 `captureFileModificationsFromTools()` 方法监听 Edit/Write 工具调用
- 自动读取修改后的文件内容，创建 FileDiff 记录（applied=true, autoApplied=true）
- 保留原有的 markdown JSON 解析作为兜底机制

**文件**：
- `src/main/java/com/dbadapter/service/ChatService.java`
- `src/main/java/com/dbadapter/entity/FileDiff.java`（新增 autoApplied 字段）
- `src/main/java/com/dbadapter/dto/Dto.java`（FileDiffResp 新增 autoApplied 字段）

#### 3. 优化全局 Skill 加载机制 ✅

**问题**：原代码试图通过 `--system-prompt-file` 加载本地 Skill，但这会覆盖 Claude 的全局 Skill。

**改造**：
- 移除 `--system-prompt-file` 参数，保持默认让 Claude 加载 `~/.claude/CLAUDE.md`
- 只使用 `--append-system-prompt` 追加数据库上下文（类型、地址、项目路径）
- 在 `application.yml` 添加 `claude.global-skill-enabled` 配置项（默认 true）
- 更新 `buildSessionCommand()` 逻辑

**文件**：
- `src/main/java/com/dbadapter/service/ClaudeCliService.java`
- `src/main/resources/application.yml`

#### 4. 前端功能增强 ✅

**改造**：
- 配置页添加"测试 Claude CLI 连接"按钮（`testClaudeConnection()`）
- 修改记录 Tab 区分"🤖 AI 已修改"和"待应用"两种状态
- 添加"回滚"按钮使用备份恢复文件（`rollbackDiff()`）
- 更新 CLI 状态面板说明全局 Skill 路径

**后端 API**：
- 新增 `POST /api/diffs/{diffId}/rollback` 回滚接口

**文件**：
- `src/main/resources/static/index.html`
- `src/main/resources/static/js/app.js`
- `src/main/java/com/dbadapter/controller/ApiController.java`

#### 5. 更新文档和配置 ✅

**改造**：
- 重写 `README.md`，与实际代码行为对齐
- 重写 `skills/README.md`，说明全局 Skill 机制和配置方法
- 更新 `application.yml` 注释
- 添加故障排查指南和推荐实践

**文件**：
- `README.md`
- `src/main/resources/skills/README.md`
- `src/main/resources/application.yml`

### 技术亮点

1. **stream-json 协议**：正确实现 Claude CLI 的流式通信，支持工具调用捕获
2. **持久进程**：每个会话一个常驻进程，保留完整上下文，避免重复初始化
3. **全局 Skill**：利用 Claude Code 的 Skill 系统，规则可复用、可版本管理
4. **自动捕获**：监听 tool_use 事件，无需 AI 输出特定格式的 JSON
5. **安全回滚**：自动备份原文件，支持一键恢复

### 架构对比

#### 改造前（不可用）
```
前端 → 后端 → claude（交互式 TUI，无法通信）❌
```

#### 改造后（完全可用）
```
前端 → 后端 → claude（stream-json 协议，持久进程）✅
                ↓
         工具调用捕获（Edit/Write）
                ↓
         自动记录文件修改前后对比
                ↓
         支持一键回滚
```

### 验证清单

改造完成后，请验证以下功能：

- [ ] 启动服务：`mvn spring-boot:run` 或 `java -jar target/db-adapter-assistant-1.0.0.jar`
- [ ] 访问 http://localhost:8080，前端页面正常显示
- [ ] 配置 Tab 中"测试连接"按钮能正确检测 Claude CLI
- [ ] 创建新会话，配置数据库类型和项目路径
- [ ] 发送消息，Claude 能正常回复（SSE 流式推送）
- [ ] Claude 使用 Edit 工具修改文件后，"修改记录" Tab 自动显示对比
- [ ] 点击"回滚"按钮能从备份恢复文件
- [ ] 重置会话后，Claude 上下文被清空
- [ ] 查看 H2 数据库，sessions/chat_messages/file_diffs 表数据正常

### 已知限制

1. **Windows 路径**：需要在 `application.yml` 中配置 `claude.cli-path` 为 `.cmd` 结尾的路径
2. **Claude CLI 版本**：需要最新版 Claude Code CLI，旧版本可能不支持 stream-json 协议
3. **文件备份**：只有通过"应用修改"按钮手动应用的修改才有备份，AI 直接修改的文件无备份（可改进）
4. **并发限制**：默认最多 3 个并发 claude 进程，可在配置中调整

### 后续优化建议

1. **文件监控**：使用 FileWatcher 监控项目目录，自动捕获所有文件变化
2. **Git 集成**：自动创建 Git commit 记录每次修改
3. **Diff 可视化**：使用 diff 算法高亮显示具体修改行
4. **批量应用**：支持一键应用所有待应用的修改
5. **导出报告**：生成适配报告（修改了哪些文件、改了什么、为什么改）

### 依赖版本

- Spring Boot: 3.2.3
- Java: 17+
- H2 Database: 内嵌（runtime scope）
- Jackson: Spring Boot 自带
- Lombok: optional

### 联系方式

如有问题，请查看：
- README.md（快速启动指南）
- skills/README.md（全局 Skill 配置）
- 后端日志（`logs/` 目录或控制台输出）
