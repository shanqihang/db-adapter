# Skills 目录说明

本目录下的所有 skill 会在应用启动时自动复制到用户全局目录 `~/.claude/skills/`，让 Claude CLI 在适配任意路径下的项目时都能加载到这些 skill。

## 目录结构

```
src/main/resources/skills/
├── README.md                    # 本说明文件
└── db-adapter/
    └── SKILL.md                 # 国产数据库适配技能（核心）
```

## 工作原理

1. **打包**：`mvn package` 时，整个 `skills/` 目录会被打包进 JAR 的 classpath
2. **启动**：`SkillInstaller.@PostConstruct` 读取 `classpath:skills/**`，递归复制到 `~/.claude/skills/`
3. **使用**：Claude CLI 启动时（无论 CWD 是哪个项目），自动从 `~/.claude/skills/` 加载所有 skill
4. **触发**：当用户消息中出现"数据库适配/MySQL 改造/达梦/金仓"等关键词时，Claude 自动加载 `db-adapter` skill

## 配置

`application.yml`:
```yaml
claude:
  skills:
    auto-install: true       # 启动时自动安装（默认 true）
    # target-dir:            # 留空使用 ~/.claude/skills，可指定其他路径
```

## 添加新 skill

1. 在 `src/main/resources/skills/` 下新建子目录，如 `my-skill/`
2. 创建 `SKILL.md`，文件头必须包含 frontmatter：
   ```markdown
   ---
   name: my-skill
   description: 简要描述（一句话）
   ---

   # 技能内容
   ...
   ```
3. 重启应用，新 skill 会自动安装到 `~/.claude/skills/my-skill/SKILL.md`

## 升级 skill

每次启动都会**覆盖**目标文件，确保用户全局目录中的 skill 始终与 JAR 内的最新版本一致。如需保留本地修改，可设置 `claude.skills.auto-install: false`。

## 与 `--append-system-prompt` 的协作

- **skill 文件**：定义"如何做"——通用的适配规则、工作流、输出格式
- **append-system-prompt**：定义"做什么"——本次会话的目标数据库、连接信息、项目路径

两者互补：skill 是知识库，append-system-prompt 是当前任务上下文。
