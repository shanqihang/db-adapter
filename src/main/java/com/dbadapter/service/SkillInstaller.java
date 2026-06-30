package com.dbadapter.service;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.file.*;
import java.util.Locale;

/**
 * Skill 安装器
 *
 * <p>应用启动时，从 classpath: <code>skills/&#42;&#42;</code> 读取所有 skill 文件，
 * 复制到用户全局目录 <code>~/.claude/skills/</code>。
 *
 * <p>这样无论 claude CLI 在哪个工作目录启动（适配任意路径下的项目），
 * 都能加载到本应用内置的数据库适配 skill。
 *
 * <p>策略：每次启动都覆盖（保证 skill 内容与 JAR 内的最新版本一致）。
 */
@Slf4j
@Service
public class SkillInstaller {

    @Value("${claude.skills.auto-install:true}")
    private boolean autoInstall;

    /** 用户全局 skill 根目录，默认 ~/.claude/skills */
    @Value("${claude.skills.target-dir:#{null}}")
    private String configuredTargetDir;

    @PostConstruct
    public void install() {
        if (!autoInstall) {
            log.info("claude.skills.auto-install=false，跳过 skill 自动安装");
            return;
        }

        Path targetRoot = resolveTargetRoot();

        try {
            Files.createDirectories(targetRoot);

            ResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            Resource[] resources = resolver.getResources("classpath:skills/**");

            int fileCount = 0;
            for (Resource resource : resources) {
                String uri = resource.getURI().toString();
                int idx = uri.indexOf("skills/");
                if (idx < 0) continue;

                String relativePath = uri.substring(idx + "skills/".length());
                if (relativePath.isEmpty() || relativePath.endsWith("/")) continue;

                Path target = targetRoot.resolve(relativePath).normalize();
                if (!target.startsWith(targetRoot)) {
                    log.warn("跳过越界 skill 路径: {}", relativePath);
                    continue;
                }

                Files.createDirectories(target.getParent());
                try (InputStream in = resource.getInputStream()) {
                    Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                    fileCount++;
                    log.debug("已安装 skill 文件: {} -> {}", relativePath, target);
                }
            }

            log.info("Skill 安装完成: {} 个文件 -> {}", fileCount, targetRoot);

        } catch (Exception e) {
            log.error("Skill 自动安装失败（不影响主流程）: {}", e.getMessage(), e);
        }
    }

    private Path resolveTargetRoot() {
        if (configuredTargetDir != null && !configuredTargetDir.isBlank()) {
            return Path.of(configuredTargetDir);
        }
        String userHome = System.getProperty("user.home");
        return Path.of(userHome, ".claude", "skills");
    }

    /** 测试用：返回当前 skill 安装目录 */
    public Path getInstallDir() {
        return resolveTargetRoot();
    }

    /** 当前 OS 是否 Windows（保留给将来需要的路径分隔符处理） */
    @SuppressWarnings("unused")
    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}
