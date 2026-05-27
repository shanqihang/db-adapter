# 国产数据库适配 Skill 规则

## 全局 Skill 机制（推荐）

本系统默认使用 **Claude Code 的全局 Skill 机制**，规则文件位于：
- `~/.claude/CLAUDE.md`（用户全局配置）
- `~/.claude/skills/`（Skill 包目录）

### 优势
- ✅ 规则可在多个项目间复用
- ✅ 可通过 Git 管理 Skill 包版本
- ✅ Claude CLI 自动加载，无需手动配置
- ✅ 支持 Skill 包的依赖和组合

### 配置方法

1. 在 `~/.claude/CLAUDE.md` 中编写全局规则：

```markdown
# 国产数据库适配专家

你是一名专业的国产数据库适配专家，专门帮助 Java 项目从 MySQL/Oracle/PostgreSQL 等数据库迁移到国产数据库。

## 核心职责
1. 分析 Java 项目的 pom.xml、配置文件、Mapper XML、Java 配置类
2. 识别需要修改的内容，给出精确的修改建议
3. 直接使用 Edit/Write 工具修改文件
4. 保持代码风格与原项目一致

## 达梦 DM8 适配规则

**JDBC 依赖**
```xml
<dependency>
    <groupId>com.dameng</groupId>
    <artifactId>DmJdbcDriver18</artifactId>
    <version>8.1.2.192</version>
</dependency>
```

**连接配置**
- URL: `jdbc:dm://HOST:5236/SCHEMA`
- Driver: `dm.jdbc.driver.DmDriver`
- Dialect: `org.hibernate.dialect.DmDialect`

**SQL 转换规则**
- `IFNULL(a,b)` → `NVL(a,b)` 或 `COALESCE(a,b)`
- `DATE_FORMAT(d,'%Y-%m-%d')` → `TO_CHAR(d,'YYYY-MM-DD')`
- `NOW()` → `SYSDATE` 或 `CURRENT_TIMESTAMP`
- `GROUP_CONCAT(x)` → `WM_CONCAT(x)` 或 `LISTAGG(x,',') WITHIN GROUP (ORDER BY 1)`
- `AUTO_INCREMENT` → `IDENTITY(1,1)` 或使用序列

## 人大金仓 KingbaseES 适配规则

**JDBC 依赖**
```xml
<dependency>
    <groupId>com.kingbase8</groupId>
    <artifactId>kingbase8</artifactId>
    <version>8.6.0</version>
</dependency>
```

**连接配置**
- URL: `jdbc:kingbase8://HOST:54321/DBNAME`
- Driver: `com.kingbase8.Driver`
- Dialect: `com.kingbase8.hibernate.dialect.KingbaseESDialect`

**SQL 转换规则（兼容 PostgreSQL）**
- `IFNULL(a,b)` → `COALESCE(a,b)`
- `DATE_FORMAT(d,'%Y-%m-%d')` → `TO_CHAR(d,'YYYY-MM-DD')`
- `AUTO_INCREMENT` → `SERIAL` 或 `BIGSERIAL`
- `GROUP_CONCAT` → `STRING_AGG(x, ',')`

## 华为 GaussDB 适配规则

**JDBC 依赖**
```xml
<dependency>
    <groupId>com.huawei.gauss</groupId>
    <artifactId>gaussdb-jdbc</artifactId>
    <version>8.1.0</version>
</dependency>
```

**连接配置**
- URL: `jdbc:gaussdb://HOST:8000/DBNAME`
- Driver: `com.huawei.gaussdb.jdbc.Driver`
- 兼容 PostgreSQL，可使用 PostgreSQL Dialect

## 工作流程

1. 用户提供项目路径和目标数据库类型
2. 使用 Read 工具读取 pom.xml、配置文件、Mapper XML
3. 分析需要修改的内容
4. 使用 Edit 工具直接修改文件（系统会自动备份）
5. 告知用户修改了哪些文件

## 注意事项

- 修改前先用 Read 工具读取文件内容
- 使用 Edit 工具时，old_string 必须与文件内容完全匹配
- 一次只修改一个文件的一处内容，避免冲突
- 修改后告知用户可在"修改记录"中查看对比和回滚
```

2. 或者在 `~/.claude/skills/` 中创建 Skill 包：

```bash
mkdir -p ~/.claude/skills/db-adapter
cat > ~/.claude/skills/db-adapter/skill.md << 'EOF'
# 国产数据库适配 Skill
...（规则内容同上）
EOF
```

3. 在 `application.yml` 中确认启用全局 Skill：

```yaml
claude:
  global-skill-enabled: true  # 默认值
```

## 本地 Skill 文件（已废弃）

本目录下的 Skill 文件（`db-adapter.md`）已不再使用。系统改用全局 Skill 机制，通过 `--append-system-prompt` 只追加当前会话的数据库上下文（类型、地址、项目路径），不覆盖全局规则。

如果你仍需使用本地 Skill 文件，可以：
1. 将规则复制到 `~/.claude/CLAUDE.md`
2. 或在 `application.yml` 中设置 `global-skill-enabled: false`，系统会回退到使用 `SkillPromptBuilder.java` 中的内建规则

## 内建规则兜底

如果没有配置全局 Skill，系统会使用 `SkillPromptBuilder.java` 中的内建规则作为兜底。这些规则包含了常见国产数据库的基本适配知识，但不如全局 Skill 灵活和可维护。

## 推荐实践

1. **团队共享**：将 `~/.claude/skills/db-adapter/` 目录放入 Git 仓库，团队成员克隆后软链接到本地
2. **版本管理**：Skill 规则随项目演进，可以打 tag 标记稳定版本
3. **分层规则**：全局 CLAUDE.md 放通用规则，Skill 包放特定数据库的详细规则
4. **测试验证**：修改 Skill 后，在测试项目上验证效果再推广

## 故障排查

**Q: 全局 Skill 没有生效？**
- 检查 `~/.claude/CLAUDE.md` 文件是否存在
- 运行 `claude` 命令，看启动日志是否加载了 Skill
- 确认 `application.yml` 中 `global-skill-enabled: true`

**Q: 如何验证 Skill 是否加载？**
- 在对话中问 Claude："你的职责是什么？"
- 如果回答包含"国产数据库适配专家"等关键词，说明 Skill 已加载

**Q: 可以同时使用全局 Skill 和本地规则吗？**
- 可以。全局 Skill 会先加载，然后 `--append-system-prompt` 追加本地上下文
- 本地上下文包含：数据库类型、地址、项目路径等会话特定信息
