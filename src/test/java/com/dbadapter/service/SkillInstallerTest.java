package com.dbadapter.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class SkillInstallerTest {

    @Test
    @DisplayName("install: 把 classpath:skills/** 复制到目标目录")
    void installsSkillFromClasspathToTargetDir(@TempDir Path tempDir) throws Exception {
        SkillInstaller installer = new SkillInstaller();
        ReflectionTestUtils.setField(installer, "autoInstall", true);
        ReflectionTestUtils.setField(installer, "configuredTargetDir", tempDir.toString());

        installer.install();

        Path skill = tempDir.resolve("db-adapter").resolve("SKILL.md");
        assertThat(skill).exists();
        String content = Files.readString(skill);
        assertThat(content).contains("name: db-adapter");
        assertThat(content).contains("数据库适配");
    }

    @Test
    @DisplayName("install: auto-install=false 时跳过安装")
    void skipsWhenDisabled(@TempDir Path tempDir) {
        SkillInstaller installer = new SkillInstaller();
        ReflectionTestUtils.setField(installer, "autoInstall", false);
        ReflectionTestUtils.setField(installer, "configuredTargetDir", tempDir.toString());

        installer.install();

        assertThat(tempDir).isEmptyDirectory();
    }

    @Test
    @DisplayName("install: 重复安装覆盖已有文件")
    void overwritesExistingFile(@TempDir Path tempDir) throws Exception {
        Path existing = tempDir.resolve("db-adapter").resolve("SKILL.md");
        Files.createDirectories(existing.getParent());
        Files.writeString(existing, "stale content");

        SkillInstaller installer = new SkillInstaller();
        ReflectionTestUtils.setField(installer, "autoInstall", true);
        ReflectionTestUtils.setField(installer, "configuredTargetDir", tempDir.toString());

        installer.install();

        String content = Files.readString(existing);
        assertThat(content).doesNotContain("stale content");
        assertThat(content).contains("name: db-adapter");
    }

    @Test
    @DisplayName("getInstallDir: 未配置时回退到 ~/.claude/skills")
    void defaultTargetDirIsUserHome() {
        SkillInstaller installer = new SkillInstaller();
        ReflectionTestUtils.setField(installer, "autoInstall", true);
        ReflectionTestUtils.setField(installer, "configuredTargetDir", null);

        Path target = installer.getInstallDir();

        assertThat(target.toString()).contains(".claude");
        assertThat(target.getFileName().toString()).isEqualTo("skills");
    }
}
