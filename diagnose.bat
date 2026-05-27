@echo off
chcp 65001 > nul
echo ========================================
echo   国产数据库适配助手 - 环境诊断
echo ========================================
echo.

REM 1. 检查 Java 版本
echo [1/5] 检查 Java 版本...
java -version 2>&1 | findstr /C:"version"
if %ERRORLEVEL% NEQ 0 (
    echo [错误] 未找到 Java，请安装 Java 17+
    goto :end
)
echo [✓] Java 已安装
echo.

REM 2. 检查 Maven
echo [2/5] 检查 Maven...
mvn -version 2>&1 | findstr /C:"Apache Maven"
if %ERRORLEVEL% NEQ 0 (
    echo [警告] 未找到 Maven，将无法编译项目
) else (
    echo [✓] Maven 已安装
)
echo.

REM 3. 检查 Claude CLI
echo [3/5] 检查 Claude CLI...
set CLAUDE_PATH=C:\Users\admin\AppData\Roaming\npm\claude.cmd
if exist "%CLAUDE_PATH%" (
    echo [✓] 找到 Claude CLI: %CLAUDE_PATH%
    "%CLAUDE_PATH%" --version 2>&1
) else (
    echo [错误] 未找到 Claude CLI
    echo 请运行: npm install -g @anthropic-ai/claude-code
    goto :end
)
echo.

REM 4. 检查 Claude 认证
echo [4/5] 检查 Claude 认证状态...
"%CLAUDE_PATH%" auth status 2>&1 | findstr /C:"Logged in"
if %ERRORLEVEL% EQU 0 (
    echo [✓] Claude 已认证
) else (
    echo [警告] Claude 未认证，请运行: claude auth login
)
echo.

REM 5. 检查配置文件
echo [5/5] 检查配置文件...
if exist "src\main\resources\application.yml" (
    echo [✓] 找到 application.yml
    findstr /C:"cli-path" src\main\resources\application.yml
) else (
    echo [错误] 未找到 application.yml
)
echo.

echo ========================================
echo   诊断完成
echo ========================================
echo.
echo 下一步:
echo 1. 如果所有检查都通过，运行: mvn spring-boot:run
echo 2. 访问 http://localhost:8080
echo 3. 在配置页点击"测试连接"验证 Claude CLI
echo.

:end
pause
