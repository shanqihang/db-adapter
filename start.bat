@echo off
REM 国产数据库适配助手（Java Edition）启动脚本

echo.
echo   ⬡  国产数据库适配助手 - Java Edition
echo   ======================================
echo.

REM 检查 Java
where java >nul 2>&1
if %errorlevel% neq 0 (
    echo ❌ 未找到 Java，请安装 Java 17+
    exit /b 1
)
for /f "tokens=3" %%a in ('java -version 2^>^&1 ^| findstr /i version') do set JAVA_VER=%%a
echo ✅ Java %JAVA_VER% 已就绪

REM 检查 Maven
where mvn >nul 2>&1
if %errorlevel% equ 0 (
    echo ✅ Maven 已就绪
    set BUILD_CMD=mvn
) else if exist ".\mvnw.cmd" (
    echo ✅ Maven Wrapper 已就绪
    set BUILD_CMD=.\mvnw.cmd
) else (
    echo ❌ 未找到 Maven，请安装 Apache Maven
    exit /b 1
)

REM 检查 Claude CLI
where claude >nul 2>&1
if %errorlevel% equ 0 (
    for /f "tokens=*" %%a in ('claude --version 2^>^&1') do echo ✅ Claude CLI: %%a
) else (
    echo ⚠️  Claude CLI 未找到，请安装：
    echo    npm install -g @anthropic-ai/claude-code
    echo    claude auth login
    echo.
)

REM 创建数据目录
if not exist "data" mkdir data
if not exist "skills" mkdir skills

REM 检查 Skill 文件
if not exist "skills\db-adapter.md" if not exist "skills\db-adapter.txt" (
    echo ℹ️  skills\ 目录下未找到 Skill 文件，将使用内建规则
    echo    可将内网 Skill 规则放到 skills\db-adapter.md
)

echo.
echo 🔨 构建项目...
%BUILD_CMD% package -DskipTests -q

if %errorlevel% neq 0 (
    echo ❌ 构建失败
    exit /b 1
)

echo 🚀 启动服务...
echo    访问地址: http://localhost:8080
echo    H2 控制台: http://localhost:8080/h2-console
echo.

REM 设置工作目录为项目根（让 skills\ 目录可被找到）
for %%f in (target\db-adapter-assistant-*.jar) do set JAR=%%f
if "%JAR%"=="" (
    echo ❌ 未找到打包的 jar 文件
    exit /b 1
)

java -jar "%JAR%" ^
    --claude.skill-dir=./skills ^
    --spring.datasource.url="jdbc:h2:file:./data/db-adapter;AUTO_SERVER=TRUE" ^
    %*