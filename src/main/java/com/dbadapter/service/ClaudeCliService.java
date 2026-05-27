package com.dbadapter.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Claude CLI 持久进程服务（stream-json 协议）
 *
 * 核心模式：--input-format stream-json + --output-format stream-json
 *   - 进程常驻，stdin 保持打开
 *   - 每次用户发消息：向 stdin 写一行 JSON
 *   - 持续从 stdout 读取流式 JSON 事件
 *   - 支持真正的多轮对话（Claude 保留上下文）
 *   - 使用 claude 全局 Skill（~/.claude/CLAUDE.md）
 *   - --dangerously-skip-permissions 允许 Claude 直接操作项目文件
 *
 * stdin 消息格式（每条消息一行 JSON）：
 *   {"type":"user","message":{"role":"user","content":"用户消息"}}
 *
 * stdout 输出格式（stream-json，每行一个事件）：
 *   {"type":"system","subtype":"init",...}
 *   {"type":"assistant","message":{"role":"assistant","content":[{"type":"text","text":"..."}]}}
 *   {"type":"tool_use","name":"Read","input":{...}}
 *   {"type":"tool_result","tool_use_id":"...","content":"..."}
 *   {"type":"result","subtype":"success"}
 */
@Slf4j
@Service
public class ClaudeCliService {

    @Value("${claude.cli-path:claude}")
    private String configuredCliPath;

    @Value("${claude.timeout-ms:300000}")
    private long timeoutMs;

    @Value("${claude.max-concurrent:5}")
    private int maxConcurrent;

    @Value("${claude.global-skill-enabled:true}")
    private boolean globalSkillEnabled;

    private static final boolean IS_WINDOWS =
            System.getProperty("os.name", "").toLowerCase().contains("win");

    private final ObjectMapper objectMapper;
    private final AtomicInteger activeProcesses = new AtomicInteger(0);

    /** 探测后确定的 claude 命令路径 */
    private volatile String resolvedCliPath;
    /** Windows 下是否需要 cmd /c 包装 */
    private volatile boolean useShellWrapper = false;

    @Autowired
    public ClaudeCliService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    // ==================== 初始化：探测 claude 路径 ====================

    @PostConstruct
    public void init() {
        resolvedCliPath = detectCliPath();
        if (resolvedCliPath != null) {
            log.info("Claude CLI 已就绪: {} (OS={}, shellWrapper={}, globalSkill={})",
                    resolvedCliPath, IS_WINDOWS ? "Windows" : "Unix", useShellWrapper, globalSkillEnabled);
        } else {
            log.warn("未找到 claude 命令，请安装 Claude Code 或在 application.yml 中配置 claude.cli-path");
        }
    }

    private String detectCliPath() {
        if (tryPath(configuredCliPath)) return configuredCliPath;
        return IS_WINDOWS ? detectWindows() : detectUnix();
    }

    private String detectWindows() {
        String withCmd = configuredCliPath.endsWith(".cmd") ? configuredCliPath : configuredCliPath + ".cmd";
        if (tryPath(withCmd)) return withCmd;

        String fromWhere = runLocator("where", "claude");
        if (fromWhere != null) {
            for (String line : fromWhere.split("\\r?\\n")) {
                line = line.trim();
                if (!line.isEmpty() && tryPath(line)) { log.info("where→{}", line); return line; }
            }
        }

        String appData = System.getenv("APPDATA");
        String userProfile = System.getenv("USERPROFILE");
        List<String> candidates = new ArrayList<>();
        if (appData != null)     candidates.add(appData + "\\npm\\claude.cmd");
        if (userProfile != null) candidates.add(userProfile + "\\AppData\\Roaming\\npm\\claude.cmd");
        candidates.add("C:\\Program Files\\nodejs\\claude.cmd");
        for (String p : candidates) if (tryPath(p)) { log.info("找到 claude: {}", p); return p; }

        if (tryShellWrapper("claude")) { useShellWrapper = true; return "claude"; }
        return null;
    }

    private String detectUnix() {
        String fromWhich = runLocator("which", "claude");
        if (fromWhich != null && !fromWhich.isBlank()) {
            fromWhich = fromWhich.trim();
            if (tryPath(fromWhich)) { log.info("which→{}", fromWhich); return fromWhich; }
        }
        for (String p : List.of(
                "/usr/local/bin/claude",
                "/usr/bin/claude",
                System.getProperty("user.home") + "/.npm-global/bin/claude",
                System.getProperty("user.home") + "/.local/bin/claude",
                "/opt/homebrew/bin/claude")) {
            if (tryPath(p)) { log.info("找到 claude: {}", p); return p; }
        }
        return null;
    }

    private boolean tryPath(String path) {
        if (path == null || path.isBlank()) return false;
        try {
            ProcessBuilder pb = new ProcessBuilder(path, "--version");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            boolean ok = p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0;
            if (ok) log.debug("tryPath({}) OK", path);
            return ok;
        } catch (Exception e) { log.debug("tryPath({}) fail: {}", path, e.getMessage()); return false; }
    }

    private boolean tryShellWrapper(String cmd) {
        try {
            ProcessBuilder pb = new ProcessBuilder("cmd.exe", "/c", cmd, "--version");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            return p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) { return false; }
    }

    private String runLocator(String locator, String target) {
        try {
            ProcessBuilder pb = new ProcessBuilder(locator, target);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            p.waitFor(3, TimeUnit.SECONDS);
            return out.isBlank() ? null : out;
        } catch (Exception e) { return null; }
    }

    // ==================== 核心：持久进程会话 ====================

    /**
     * 启动一个 claude 持久进程会话（stream-json 模式）。
     *
     * @param projectPath        项目根目录，claude 将在此目录下运行，可直接读写文件
     * @param appendSystemPrompt 追加到 claude 全局 Skill 后面的上下文（数据库类型等）
     * @return ClaudeSession 封装对象，调用方通过它发消息、读取流式回复
     */
    public ClaudeSession startSession(String projectPath, String appendSystemPrompt) throws IOException {
        if (resolvedCliPath == null) throw new IllegalStateException(buildNotFoundMsg());
        if (activeProcesses.get() >= maxConcurrent)
            throw new IllegalStateException("并发会话已达上限（" + maxConcurrent + "），请等待其他会话结束");

        List<String> cmd = buildSessionCommand(appendSystemPrompt);
        log.info("启动 claude 会话: {} (cwd={})", String.join(" ", cmd), projectPath);

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(false);

        // 工作目录设为项目根路径 → claude 可直接访问项目文件
        if (projectPath != null && !projectPath.isBlank() && Files.isDirectory(Path.of(projectPath))) {
            pb.directory(new File(projectPath));
        }
        pb.environment().putAll(System.getenv());

        Process process = pb.start();
        activeProcesses.incrementAndGet();

        ClaudeSession session = new ClaudeSession(process, objectMapper, () -> activeProcesses.decrementAndGet());

        // 等待 claude 初始化完成（收到 system init 事件）
        session.waitForInit(10_000);
        log.info("claude 会话就绪 (pid={})", process.pid());

        return session;
    }

    /**
     * 构建启动命令（stream-json 交互式会话模式）
     */
    private List<String> buildSessionCommand(String appendSystemPrompt) {
        List<String> cmd = new ArrayList<>();

        // Windows 下 .cmd/.bat 文件必须通过 cmd.exe /c 启动
        // Java 17+ 出于安全考虑禁止 ProcessBuilder 直接执行 .cmd/.bat
        boolean needShellWrapper = IS_WINDOWS && (
                useShellWrapper
                || resolvedCliPath.toLowerCase().endsWith(".cmd")
                || resolvedCliPath.toLowerCase().endsWith(".bat"));

        if (needShellWrapper) {
            cmd.add("cmd.exe");
            cmd.add("/c");
        }

        cmd.add(resolvedCliPath);

        // stream-json 协议（核心）
        cmd.add("--input-format");
        cmd.add("stream-json");
        cmd.add("--output-format");
        cmd.add("stream-json");
        // stream-json 输出模式需要 --verbose 和 --print
        cmd.add("--verbose");
        cmd.add("--print");

        // 允许操作文件（适配改造需要读写项目文件）
        cmd.add("--dangerously-skip-permissions");

        // 追加数据库上下文到全局 Skill 后面（不使用 --system-prompt 以保留全局 Skill）
        if (appendSystemPrompt != null && !appendSystemPrompt.isBlank()) {
            cmd.add("--append-system-prompt");
            cmd.add(appendSystemPrompt);
        }

        return cmd;
    }

    // ==================== 公开工具方法 ====================

    public boolean isCliAvailable() {
        if (resolvedCliPath == null) resolvedCliPath = detectCliPath();
        return resolvedCliPath != null;
    }

    public String getResolvedCliPath() {
        return resolvedCliPath != null ? resolvedCliPath : "未找到";
    }

    public int getActiveProcessCount() {
        return activeProcesses.get();
    }

    private String buildNotFoundMsg() {
        return IS_WINDOWS
                ? "找不到 claude 命令。请安装：npm install -g @anthropic-ai/claude-code\n" +
                  "安装后重启服务，或在 application.yml 中配置 claude.cli-path"
                : "找不到 claude 命令。请安装：npm install -g @anthropic-ai/claude-code\n" +
                  "并登录：claude auth login";
    }

    // ==================== ClaudeSession 内部类 ====================

    /**
     * 封装一个持久的 claude CLI 进程会话（stream-json 协议）。
     * 线程安全：sendMessage 可被多个线程调用（内部用锁保护 stdin 写入）。
     */
    public static class ClaudeSession implements Closeable {

        private final Process process;
        private final ObjectMapper objectMapper;
        private final Runnable onClose;
        private final PrintWriter stdin;
        private final BufferedReader stdout;
        private final BufferedReader stderr;

        /** 初始化完成的信号 */
        private final CountDownLatch initLatch = new CountDownLatch(1);

        /** 当前是否有消息正在处理中（防止并发写入同一进程） */
        private volatile boolean processing = false;

        /** stderr 消费线程 */
        private final Thread stderrThread;

        private volatile boolean closed = false;

        ClaudeSession(Process process, ObjectMapper objectMapper, Runnable onClose) {
            this.process = process;
            this.objectMapper = objectMapper;
            this.onClose = onClose;
            this.stdin = new PrintWriter(
                    new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8)));
            this.stdout = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
            this.stderr = new BufferedReader(
                    new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8));

            // 启动 stderr 消费线程（避免缓冲区满阻塞）
            this.stderrThread = new Thread(() -> {
                try {
                    String line;
                    while (!closed && (line = stderr.readLine()) != null) {
                        log.debug("[claude stderr] {}", line);
                    }
                } catch (IOException ignored) {}
            }, "claude-session-stderr");
            this.stderrThread.setDaemon(true);
            this.stderrThread.start();
        }

        /**
         * 等待 claude 进程就绪（stream-json 模式下等待 init 事件）
         */
        void waitForInit(long timeoutMs) {
            Thread initReader = new Thread(() -> {
                try {
                    String line;
                    while ((line = stdout.readLine()) != null) {
                        log.debug("[claude init] {}", line);
                        try {
                            JsonNode event = objectMapper.readTree(line);
                            String type = event.path("type").asText();
                            if ("system".equals(type) && "init".equals(event.path("subtype").asText())) {
                                log.info("收到 claude init 事件");
                                initLatch.countDown();
                                break;
                            }
                        } catch (Exception e) {
                            log.debug("解析 init 事件失败: {}", e.getMessage());
                        }
                    }
                } catch (Exception e) {
                    log.debug("等待 init 中断: {}", e.getMessage());
                    initLatch.countDown();
                }
            }, "claude-init-reader");
            initReader.setDaemon(true);
            initReader.start();

            try {
                boolean ok = initLatch.await(timeoutMs, TimeUnit.MILLISECONDS);
                if (!ok) {
                    log.warn("claude init 等待超时，继续运行");
                    initLatch.countDown();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        /**
         * 发送消息并流式消费回复（stream-json 协议）。
         *
         * @param userMessage 用户消息文本
         * @param onChunk     每收到文本片段时的回调
         * @param onToolUse   收到工具调用事件时的回调（name, input, tool_use_id）
         * @param onDone      本轮回复完成时的回调（完整文本）
         * @param onError     出错时的回调
         */
        public synchronized void sendMessage(
                String userMessage,
                Consumer<String> onChunk,
                Consumer<ToolUseEvent> onToolUse,
                Consumer<String> onDone,
                Consumer<String> onError) {

            if (closed || !process.isAlive()) {
                onError.accept("claude 会话已关闭，请重新启动会话");
                return;
            }

            if (processing) {
                onError.accept("当前有消息正在处理中，请等待完成后再发送");
                return;
            }

            processing = true;
            try {
                // 构建 stream-json 格式的用户消息
                ObjectNode userMsg = objectMapper.createObjectNode();
                userMsg.put("type", "user");
                ObjectNode message = objectMapper.createObjectNode();
                message.put("role", "user");
                message.put("content", userMessage);
                userMsg.set("message", message);

                String jsonLine = objectMapper.writeValueAsString(userMsg);
                log.debug("→ stdin: {}", jsonLine);
                stdin.println(jsonLine);
                stdin.flush();

                // 读取 stdout 流式 JSON 事件
                StringBuilder fullText = new StringBuilder();
                String line;

                while ((line = stdout.readLine()) != null) {
                    log.debug("← stdout: {}", line);

                    try {
                        JsonNode event = objectMapper.readTree(line);
                        String type = event.path("type").asText();

                        switch (type) {
                            case "assistant" -> {
                                // 提取文本内容
                                JsonNode content = event.path("message").path("content");
                                if (content.isArray()) {
                                    for (JsonNode item : content) {
                                        if ("text".equals(item.path("type").asText())) {
                                            String text = item.path("text").asText();
                                            fullText.append(text);
                                            onChunk.accept(text);
                                        }
                                    }
                                }
                            }
                            case "tool_use" -> {
                                // 工具调用事件
                                String toolName = event.path("name").asText();
                                String toolUseId = event.path("id").asText();
                                JsonNode input = event.path("input");
                                if (onToolUse != null) {
                                    onToolUse.accept(new ToolUseEvent(toolName, input, toolUseId));
                                }
                            }
                            case "tool_result" -> {
                                // 工具执行结果（暂时只记录日志）
                                log.debug("tool_result: {}", event.path("content").asText());
                            }
                            case "result" -> {
                                // 本轮对话结束
                                String subtype = event.path("subtype").asText();
                                if ("success".equals(subtype)) {
                                    onDone.accept(fullText.toString());
                                    return;
                                } else {
                                    onError.accept("claude 返回错误: " + event.path("error").asText());
                                    return;
                                }
                            }
                            case "system" -> {
                                // 系统事件（跳过 init，其他记录日志）
                                String subtype = event.path("subtype").asText();
                                if (!"init".equals(subtype)) {
                                    log.debug("system event: {}", subtype);
                                }
                            }
                        }
                    } catch (Exception e) {
                        log.warn("解析 stream-json 事件失败: {}", e.getMessage());
                    }
                }

                // stdout 关闭（进程退出）
                if (process.isAlive()) {
                    if (fullText.length() > 0) {
                        onDone.accept(fullText.toString());
                    } else {
                        onError.accept("claude 输出流意外关闭");
                    }
                } else {
                    int exitCode = process.exitValue();
                    if (exitCode == 0 && fullText.length() > 0) {
                        onDone.accept(fullText.toString());
                    } else {
                        onError.accept("claude 进程退出，退出码: " + exitCode);
                    }
                }

            } catch (Exception e) {
                log.error("sendMessage 异常", e);
                onError.accept("发送消息失败: " + e.getMessage());
            } finally {
                processing = false;
            }
        }

        public boolean isAlive() {
            return !closed && process.isAlive();
        }

        public boolean isProcessing() {
            return processing;
        }

        public long getPid() {
            return process.pid();
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            try {
                // 发送 EOF 给 claude
                stdin.close();
                // 给进程 2 秒优雅退出
                if (!process.waitFor(2, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
                log.info("claude 会话已关闭 (pid={})", process.pid());
            } catch (Exception e) {
                log.debug("关闭 claude 会话: {}", e.getMessage());
            } finally {
                onClose.run();
                stderrThread.interrupt();
            }
        }
    }

    /**
     * 工具调用事件
     */
    public static class ToolUseEvent {
        public final String name;
        public final JsonNode input;
        public final String toolUseId;

        public ToolUseEvent(String name, JsonNode input, String toolUseId) {
            this.name = name;
            this.input = input;
            this.toolUseId = toolUseId;
        }
    }
}
