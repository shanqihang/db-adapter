package com.dbadapter.service;

import com.dbadapter.dto.Dto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

@Slf4j
@Service
public class FileService {

    private static final int MAX_FILE_SIZE_KB = 200;
    private static final int MAX_FILES_PER_TYPE = 30;

    private static final Set<String> SKIP_DIRS = Set.of(
            ".git", "node_modules", ".idea", ".mvn",
            "target", "build", ".gradle", "__pycache__", ".DS_Store"
    );

    /**
     * 扫描 Java 项目，返回关键文件清单
     */
    public Dto.ScanResult scanProject(String projectPath) {
        Dto.ScanResult result = new Dto.ScanResult();
        result.setProjectPath(projectPath);

        Path root = Path.of(projectPath);
        if (!Files.exists(root) || !Files.isDirectory(root)) {
            result.setExists(false);
            result.setError("路径不存在或不是目录: " + projectPath);
            return result;
        }
        result.setExists(true);

        List<String> pomFiles = new ArrayList<>();
        List<String> configFiles = new ArrayList<>();
        List<String> mapperFiles = new ArrayList<>();
        List<String> javaFiles = new ArrayList<>();

        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (SKIP_DIRS.contains(dir.getFileName().toString())) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    String name = file.getFileName().toString();
                    String lower = name.toLowerCase();
                    String abs = file.toAbsolutePath().toString();

                    // pom.xml
                    if (name.equals("pom.xml") && pomFiles.size() < MAX_FILES_PER_TYPE) {
                        pomFiles.add(abs);
                    }
                    // 配置文件
                    else if (isConfigFile(lower) && configFiles.size() < MAX_FILES_PER_TYPE) {
                        configFiles.add(abs);
                    }
                    // Mapper XML
                    else if (lower.endsWith(".xml") && mapperFiles.size() < MAX_FILES_PER_TYPE) {
                        if (isMapperXml(file)) {
                            mapperFiles.add(abs);
                        }
                    }
                    // Java 配置类
                    else if (lower.endsWith(".java") && isDbConfigJava(lower)) {
                        if (javaFiles.size() < MAX_FILES_PER_TYPE) {
                            javaFiles.add(abs);
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    log.debug("跳过无法访问的文件: {}", file);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            log.error("扫描项目出错", e);
            result.setError("扫描出错: " + e.getMessage());
        }

        Dto.ScanResult.ScanFiles files = new Dto.ScanResult.ScanFiles();
        files.setPom(pomFiles);
        files.setConfig(configFiles);
        files.setMapper(mapperFiles);
        files.setJava(javaFiles);
        result.setFiles(files);

        Dto.ScanResult.ScanSummary summary = new Dto.ScanResult.ScanSummary();
        summary.setPomCount(pomFiles.size());
        summary.setConfigCount(configFiles.size());
        summary.setMapperCount(mapperFiles.size());
        summary.setJavaConfigCount(javaFiles.size());
        result.setSummary(summary);

        return result;
    }

    /**
     * 读取文件内容（限制大小）
     */
    public String readFile(String filePath) throws IOException {
        Path path = Path.of(filePath);
        if (!Files.exists(path)) throw new FileNotFoundException("文件不存在: " + filePath);

        long size = Files.size(path);
        if (size > MAX_FILE_SIZE_KB * 1024L) {
            // 超大文件只读前 N KB
            byte[] buf = new byte[MAX_FILE_SIZE_KB * 1024];
            try (InputStream is = Files.newInputStream(path)) {
                int read = is.read(buf);
                return new String(buf, 0, read, StandardCharsets.UTF_8)
                        + "\n\n... [文件过大，已截取前 " + MAX_FILE_SIZE_KB + "KB] ...";
            }
        }

        return Files.readString(path, StandardCharsets.UTF_8);
    }

    /**
     * 构建项目上下文（拼接关键文件内容，发给 AI）
     */
    public String buildProjectContext(String projectPath, Dto.ScanResult.ScanFiles files) {
        StringBuilder ctx = new StringBuilder();
        ctx.append("# Java 项目文件内容\n\n");

        // pom.xml（取第一个根 pom）
        addFilesToContext(ctx, projectPath, files.getPom(), 2, "pom.xml");

        // 配置文件
        addFilesToContext(ctx, projectPath, files.getConfig(), 5, "配置文件");

        // Mapper XML（样本）
        addFilesToContext(ctx, projectPath, files.getMapper(), 5, "Mapper XML（样本）");

        // Java 配置类
        addFilesToContext(ctx, projectPath, files.getJava(), 5, "Java 配置类");

        return ctx.toString();
    }

    private void addFilesToContext(StringBuilder ctx, String projectPath,
                                   List<String> files, int maxCount, String label) {
        if (files == null || files.isEmpty()) return;
        ctx.append("## ").append(label).append("\n\n");

        int count = 0;
        for (String filePath : files) {
            if (count++ >= maxCount) {
                ctx.append("_（还有 ").append(files.size() - maxCount).append(" 个文件未展示）_\n\n");
                break;
            }
            try {
                String relPath = makeRelative(projectPath, filePath);
                String content = readFile(filePath);
                ctx.append("### ").append(relPath).append("\n");
                ctx.append("```\n").append(content).append("\n```\n\n");
            } catch (Exception e) {
                log.debug("读取文件失败: {}", filePath);
            }
        }
    }

    /**
     * 应用修改到文件（先备份，再写入）
     */
    public ApplyResult applyModification(String filePath, String original, String modified) {
        try {
            Path path = Path.of(filePath);
            if (!Files.exists(path)) {
                return ApplyResult.fail("文件不存在: " + filePath);
            }

            String currentContent = readFile(filePath);

            // 检查 original 是否存在于文件中
            if (!currentContent.contains(original)) {
                return ApplyResult.fail("原内容在文件中未找到，可能已被修改。请重新分析后再应用。");
            }

            // 备份原文件
            String backupPath = filePath + ".bak." + System.currentTimeMillis();
            Files.copy(path, Path.of(backupPath), StandardCopyOption.REPLACE_EXISTING);

            // 替换内容（只替换第一次出现）
            String newContent = currentContent.replaceFirst(
                    java.util.regex.Pattern.quote(original), modified);
            Files.writeString(path, newContent, StandardCharsets.UTF_8);

            log.info("已应用修改到文件: {}, 备份: {}", filePath, backupPath);
            return ApplyResult.ok(backupPath);

        } catch (Exception e) {
            log.error("应用修改失败: {}", filePath, e);
            return ApplyResult.fail(e.getMessage());
        }
    }

    // ========== 内部工具方法 ==========

    private boolean isConfigFile(String name) {
        return name.equals("application.yml")
                || name.equals("application.yaml")
                || name.equals("application.properties")
                || name.matches("application-.*\\.(yml|yaml|properties)")
                || name.equals("druid.properties")
                || name.equals("database.properties")
                || name.equals("mybatis-config.xml")
                || name.equals("mybatis-plus-config.xml");
    }

    private boolean isMapperXml(Path file) {
        try {
            // 只读前 512 字节判断
            byte[] buf = new byte[512];
            try (InputStream is = Files.newInputStream(file)) {
                int read = is.read(buf);
                String head = new String(buf, 0, read, StandardCharsets.UTF_8);
                return head.contains("<!DOCTYPE mapper") || head.contains("<mapper")
                        || head.contains("namespace=");
            }
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isDbConfigJava(String name) {
        return name.contains("config") || name.contains("datasource")
                || name.contains("database") || name.contains("mybatis")
                || name.contains("druid") || name.contains("jdbc");
    }

    private String makeRelative(String basePath, String filePath) {
        try {
            return Path.of(basePath).relativize(Path.of(filePath)).toString();
        } catch (Exception e) {
            return filePath;
        }
    }

    // ========== 结果类 ==========

    public record ApplyResult(boolean success, String backupPath, String error) {
        public static ApplyResult ok(String backupPath) {
            return new ApplyResult(true, backupPath, null);
        }
        public static ApplyResult fail(String error) {
            return new ApplyResult(false, null, error);
        }
    }
}
