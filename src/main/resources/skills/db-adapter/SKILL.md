---
name: db-adapter
description: Java项目国产数据库适配改造工具，支持MySQL到达梦、金仓、神通等12种数据库的自动化适配
---

# Java 项目国产数据库适配技能

## 概述

本技能用于帮助用户将 Java 项目从 MySQL 适配到国产数据库（达梦、金仓、神通、瀚高、海量、优炫、南大通用PG、虚谷、崖山等）。

## 适配工作流

### 阶段 1：分析模式（Analysis Phase）

**目标**：扫描项目、识别适配点、输出方案

**严格规则**：
- ✅ 允许：Read、Grep、Glob 等只读工具
- ❌ 禁止：Edit、Write、NotebookEdit 等修改文件的工具
- ❌ 禁止：对项目文件做任何写入操作

**分析步骤**：

1. **扫描构建配置**
   - 读取 `pom.xml` 或 `build.gradle`
   - 识别 MySQL 驱动依赖
   - 检查 MyBatis/Hibernate 等 ORM 框架版本

2. **扫描配置文件**
   - 检查 `application.yml`、`application.properties`
   - 识别数据库连接配置（JDBC URL、驱动类）
   - 检查连接池配置（HikariCP、Druid 等）

3. **扫描 SQL 文件**
   - 检查 MyBatis Mapper XML 文件
   - 识别 MySQL 特有语法（LIMIT、AUTO_INCREMENT、函数等）
   - 检查存储过程、触发器定义

4. **扫描 Java 代码**
   - 搜索硬编码的 SQL 语句
   - 检查 `@Query` 注解中的 SQL
   - 识别使用 MySQL 特有 API 的代码

5. **输出适配方案**（JSON 格式）

```json
{
  "modifications": [
    {
      "filePath": "pom.xml",
      "description": "替换 MySQL 驱动为目标数据库驱动",
      "original": "<dependency>\n    <groupId>mysql</groupId>\n    <artifactId>mysql-connector-java</artifactId>\n</dependency>",
      "modified": "<dependency>\n    <groupId>com.dameng</groupId>\n    <artifactId>DmJdbcDriver18</artifactId>\n    <version>8.1.2.141</version>\n</dependency>"
    }
  ]
}
```

### 阶段 2：执行模式（Execution Phase）

**前提**：用户已确认方案

**执行步骤**：
1. 逐项应用 JSON 方案中的修改
2. 使用 Edit 工具精确替换（基于 original 匹配）
3. 每完成一处修改，简要确认
4. 如发现问题，先说明再决定是否继续

---

## 数据库适配规则

### 1. 驱动依赖替换

**MySQL 驱动移除**：
```xml
<dependency>
    <groupId>mysql</groupId>
    <artifactId>mysql-connector-java</artifactId>
</dependency>
```

**目标数据库驱动添加**：

| 数据库 | groupId | artifactId | 推荐版本 |
|--------|---------|------------|----------|
| 达梦 DM8 | com.dameng | DmJdbcDriver18 | 8.1.2.141 |
| 金仓 V8R6 | cn.com.kingbase | kingbase8 | 8.6.0 |
| 金仓 V9 | com.kingbase | kingbase9-jdbc | 9.0.0 |
| 神通 | com.oscar | oscarJDBC | 6.0.0 |
| 瀚高 HighGo | com.highgo | HgdbJdbc | 6.0.5 |
| 海量 Vastbase | com.vastdata | vastbase-jdbc | 2.2.0 |
| 优炫 | com.uxsino | uxdb-driver | 2.1 |
| 南大通用PG | com.gbase | gbase-connector-java | 8.3.81 |
| 虚谷 | com.xugu | xugu-jdbc | 12.2.0 |
| 崖山 | com.yashandb | yashandb-jdbc | 23.1 |

### 2. JDBC URL 格式

| 数据库 | JDBC URL 格式 | 驱动类 |
|--------|---------------|--------|
| MySQL | `jdbc:mysql://host:port/db?params` | `com.mysql.cj.jdbc.Driver` |
| 达梦 DM8 | `jdbc:dm://host:port/db?params` | `dm.jdbc.driver.DmDriver` |
| 金仓 V8 | `jdbc:kingbase8://host:port/db?params` | `com.kingbase8.Driver` |
| 金仓 V9 | `jdbc:kingbase9://host:port/db?params` | `com.kingbase.Driver` |
| 神通 | `jdbc:oscar://host:port/db` | `com.oscar.Driver` |
| 瀚高 | `jdbc:highgo://host:port/db` | `com.highgo.jdbc.Driver` |
| 海量 | `jdbc:vastbase://host:port/db` | `com.vastdata.Driver` |
| 优炫 | `jdbc:uxdb://host:port/db` | `com.uxsino.uxdb.Driver` |
| 南大通用PG | `jdbc:gbase://host:port/db` | `com.gbase.jdbc.Driver` |
| 虚谷 | `jdbc:xugu://host:port/db` | `com.xugu.cloudjdbc.Driver` |
| 崖山 | `jdbc:yashandb://host:port/db` | `com.yashandb.jdbc.Driver` |

**示例**：
```yaml
# MySQL
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/mydb?useUnicode=true&characterEncoding=utf8
    driver-class-name: com.mysql.cj.jdbc.Driver

# 达梦 DM8
spring:
  datasource:
    url: jdbc:dm://localhost:5236/mydb?characterEncoding=utf8
    driver-class-name: dm.jdbc.driver.DmDriver
```

### 3. SQL 语法适配

#### 3.1 分页查询

**MySQL**: `SELECT * FROM user LIMIT 10 OFFSET 20;`

- **达梦/金仓/神通/瀚高/海量/优炫/南大通用PG/崖山**：支持标准 `LIMIT ... OFFSET ...`
- **虚谷**：支持 `LIMIT 20, 10` MySQL 语法

#### 3.2 自增主键

**MySQL**: `id BIGINT AUTO_INCREMENT PRIMARY KEY`

- **达梦**: `id BIGINT IDENTITY(1,1) PRIMARY KEY`
- **金仓/神通/瀚高/海量/优炫/南大通用PG**: `id BIGSERIAL PRIMARY KEY`
- **虚谷/崖山**: 支持 `AUTO_INCREMENT`

#### 3.3 日期时间函数

| MySQL | 达梦 | 金仓/PG类 | 虚谷/崖山 |
|-------|------|-----------|------|
| `NOW()` | `SYSDATE` | `NOW()` | `NOW()` |
| `CURDATE()` | `TRUNC(SYSDATE)` | `CURRENT_DATE` | `CURDATE()` |
| `DATE_FORMAT(date, '%Y-%m-%d')` | `TO_CHAR(date, 'YYYY-MM-DD')` | `TO_CHAR(date, 'YYYY-MM-DD')` | `DATE_FORMAT(date, '%Y-%m-%d')` |

#### 3.4 反引号标识符

**MySQL**: 使用反引号 `` `table` `` 包裹关键字

- **达梦/金仓/神通/瀚高/海量/优炫/南大通用PG**：使用双引号 `"table"`
- **虚谷/崖山**：支持反引号，也支持双引号

### 4. MyBatis 配置适配

**application.yml**:
```yaml
mybatis:
  configuration:
    database-id: MySQL  # 改为目标数据库，如 DM、KingBase
```

**映射文件中使用 databaseId**：
```xml
<select id="findUsers" resultType="User" databaseId="DM">
    SELECT * FROM user LIMIT #{limit} OFFSET #{offset}
</select>
```

### 5. JPA/Hibernate 配置适配

**Hibernate 方言映射**：

| 数据库 | Hibernate Dialect |
|--------|-------------------|
| 达梦 DM8 | `org.hibernate.dialect.DmDialect` (需自定义) |
| 金仓/神通/瀚高/海量/优炫/南大通用PG | `org.hibernate.dialect.PostgreSQLDialect` |

### 6. 连接池配置调整

| 数据库 | 测试查询 |
|--------|----------|
| 达梦 | `SELECT 1 FROM DUAL` |
| 金仓/类PG/虚谷/崖山 | `SELECT 1` |

---

## 输出规范

### 分析阶段输出格式

1. **文字说明**（中文）：项目类型、ORM 框架、适配点总结
2. **JSON 修改方案**：

```json
{
  "modifications": [
    {
      "filePath": "相对于项目根目录的路径",
      "description": "修改说明（中文，详细描述为什么要改、改成什么）",
      "original": "文件中需要被替换的原始内容（必须精确匹配，包括空格和换行）",
      "modified": "替换后的新内容"
    }
  ]
}
```

### 执行阶段输出格式

每完成一处修改输出：`✅ 已修改：<filePath>`

完成后输出总结：
```
🎉 数据库适配完成！
修改文件清单：
- pom.xml
- src/main/resources/application.yml
下一步建议：
1. 手动检查 SQL 语法是否全部适配
2. 运行单元测试验证功能
3. 连接目标数据库进行集成测试
```

---

## 注意事项

1. **备份优先**：执行前建议用户备份代码或提交到 Git
2. **分批验证**：修改后立即验证，不要一次性改完再测试
3. **兼容性测试**：适配完成后务必进行完整的回归测试
4. **字符集设置**：确保 JDBC URL 中指定了正确的字符集

---

## 更新记录

- 2026-06-26: 初始版本，支持 12 种国产数据库适配规则
