#!/bin/bash
# 国产数据库适配助手（Java Edition）启动脚本

echo ""
echo "  ⬡  国产数据库适配助手 - Java Edition"
echo "  ======================================"
echo ""

# 检查 Java
if ! command -v java &>/dev/null; then
    echo "❌ 未找到 Java，请安装 Java 17+"
    exit 1
fi
JAVA_VER=$(java -version 2>&1 | awk -F '"' '/version/ {print $2}' | cut -d. -f1)
echo "✅ Java $JAVA_VER 已就绪"

# 检查 Maven
if command -v mvn &>/dev/null; then
    echo "✅ Maven 已就绪"
    BUILD_CMD="mvn"
elif [ -f "./mvnw" ]; then
    echo "✅ Maven Wrapper 已就绪"
    BUILD_CMD="./mvnw"
else
    echo "❌ 未找到 Maven，请安装 Apache Maven 或运行 mvn wrapper:wrapper"
    exit 1
fi

# 检查 Claude CLI
if command -v claude &>/dev/null; then
    CLAUDE_VER=$(claude --version 2>&1 | head -1)
    echo "✅ Claude CLI: $CLAUDE_VER"
else
    echo "⚠️  Claude CLI 未找到，请安装："
    echo "   npm install -g @anthropic-ai/claude-code"
    echo "   claude auth login"
    echo ""
fi

# 创建数据目录
mkdir -p data skills

# 检查 Skill 文件
if [ ! -f "skills/db-adapter.md" ] && [ ! -f "skills/db-adapter.txt" ]; then
    echo "ℹ️  skills/ 目录下未找到 Skill 文件，将使用内建规则"
    echo "   可将内网 Skill 规则放到 skills/db-adapter.md"
fi

echo ""
echo "🔨 构建项目..."
$BUILD_CMD package -DskipTests -q

if [ $? -ne 0 ]; then
    echo "❌ 构建失败"
    exit 1
fi

echo "🚀 启动服务..."
echo "   访问地址: http://localhost:8080"
echo "   H2 控制台: http://localhost:8080/h2-console"
echo ""

# 设置工作目录为项目根（让 skills/ 目录可被找到）
JAR=$(ls target/db-adapter-assistant-*.jar 2>/dev/null | head -1)
if [ -z "$JAR" ]; then
    echo "❌ 未找到打包的 jar 文件"
    exit 1
fi

java -jar "$JAR" \
    --claude.skill-dir=./skills \
    --spring.datasource.url="jdbc:h2:file:./data/db-adapter;AUTO_SERVER=TRUE" \
    "$@"
