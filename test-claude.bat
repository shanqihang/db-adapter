@echo off
REM 测试 Claude CLI stream-json 模式启动

echo 测试 1: 检查 claude 版本
"C:\Users\admin\AppData\Roaming\npm\claude.cmd" --version
if %ERRORLEVEL% NEQ 0 (
    echo [错误] claude 命令执行失败
    exit /b 1
)

echo.
echo 测试 2: 测试 stream-json 模式启动（5秒后自动退出）
echo 启动命令: claude --input-format stream-json --output-format stream-json --verbose --print --dangerously-skip-permissions
echo.

REM 创建临时测试脚本
echo {"type":"user","message":{"role":"user","content":"hello"}} > test_input.json

REM 启动 claude 并在 5 秒后强制退出
start /b "" "C:\Users\admin\AppData\Roaming\npm\claude.cmd" --input-format stream-json --output-format stream-json --verbose --print --dangerously-skip-permissions < test_input.json > test_output.json 2>&1

timeout /t 5 /nobreak > nul

echo.
echo 输出内容:
type test_output.json

REM 清理
del test_input.json test_output.json 2>nul

echo.
echo 测试完成！如果看到 JSON 格式的输出（包含 "type":"system" 或 "type":"assistant"），说明 stream-json 模式正常工作。
