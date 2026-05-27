package com.dbadapter.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileServiceTest {

    private FileService fileService;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        fileService = new FileService();
    }

    // ==================== scanProject ====================

    @Test
    @DisplayName("scanProject: 路径不存在返回 exists=false")
    void scanNonExistentPath() {
        var result = fileService.scanProject("/nonexistent/path/xyz");
        assertThat(result.isExists()).isFalse();
        assertThat(result.getError()).isNotBlank();
    }

    @Test
    @DisplayName("scanProject: 空项目目录正常返回 0 文件")
    void scanEmptyDir() {
        var result = fileService.scanProject(tempDir.toString());
        assertThat(result.isExists()).isTrue();
        assertThat(result.getSummary().getPomCount()).isEqualTo(0);
        assertThat(result.getSummary().getConfigCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("scanProject: 能找到 pom.xml 和 application.yml")
    void scanWithPomAndConfig() throws IOException {
        Files.writeString(tempDir.resolve("pom.xml"),
                "<project><groupId>com.test</groupId></project>");
        Files.writeString(tempDir.resolve("application.yml"),
                "spring:\n  datasource:\n    url: jdbc:mysql://localhost/test");

        var result = fileService.scanProject(tempDir.toString());
        assertThat(result.isExists()).isTrue();
        assertThat(result.getSummary().getPomCount()).isEqualTo(1);
        assertThat(result.getSummary().getConfigCount()).isEqualTo(1);
        assertThat(result.getFiles().getPom()).hasSize(1);
        assertThat(result.getFiles().getPom().get(0)).endsWith("pom.xml");
    }

    @Test
    @DisplayName("scanProject: 能识别 Mapper XML 文件")
    void scanMapperXml() throws IOException {
        Path mapperDir = tempDir.resolve("src/main/resources/mapper");
        Files.createDirectories(mapperDir);
        Files.writeString(mapperDir.resolve("UserMapper.xml"),
                "<?xml version=\"1.0\"?>\n<!DOCTYPE mapper PUBLIC \"-//mybatis.org//DTD Mapper 3.0//EN\"\n" +
                "  \"http://mybatis.org/dtd/mybatis-3-mapper.dtd\">\n<mapper namespace=\"com.UserMapper\">\n</mapper>");

        var result = fileService.scanProject(tempDir.toString());
        assertThat(result.getSummary().getMapperCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("scanProject: 跳过 target 目录")
    void scanSkipsTargetDir() throws IOException {
        Path targetDir = tempDir.resolve("target");
        Files.createDirectories(targetDir);
        Files.writeString(targetDir.resolve("pom.xml"), "<project/>");
        // 根目录不放 pom，只在 target 下放
        var result = fileService.scanProject(tempDir.toString());
        assertThat(result.getSummary().getPomCount()).isEqualTo(0);
    }

    // ==================== readFile ====================

    @Test
    @DisplayName("readFile: 正常读取文件内容")
    void readExistingFile() throws IOException {
        Path f = tempDir.resolve("test.txt");
        Files.writeString(f, "hello world");
        String content = fileService.readFile(f.toString());
        assertThat(content).isEqualTo("hello world");
    }

    @Test
    @DisplayName("readFile: 文件不存在抛出异常")
    void readMissingFile() {
        assertThatThrownBy(() -> fileService.readFile("/nonexistent/file.txt"))
                .isInstanceOf(Exception.class);
    }

    // ==================== applyModification ====================

    @Test
    @DisplayName("applyModification: 正常替换内容并创建备份")
    void applyModificationSuccess() throws IOException {
        Path f = tempDir.resolve("pom.xml");
        Files.writeString(f, "<dependency><groupId>mysql</groupId></dependency>");

        var result = fileService.applyModification(
                f.toString(),
                "<groupId>mysql</groupId>",
                "<groupId>com.dameng</groupId>"
        );

        assertThat(result.success()).isTrue();
        assertThat(result.backupPath()).isNotNull();
        assertThat(Files.readString(f)).contains("com.dameng");
        assertThat(Files.readString(f)).doesNotContain("<groupId>mysql</groupId>");
        // 备份文件存在
        assertThat(Path.of(result.backupPath())).exists();
    }

    @Test
    @DisplayName("applyModification: original 不存在时返回 fail")
    void applyModificationOriginalNotFound() throws IOException {
        Path f = tempDir.resolve("pom.xml");
        Files.writeString(f, "<project>content</project>");

        var result = fileService.applyModification(
                f.toString(),
                "THIS_STRING_DOES_NOT_EXIST",
                "replacement"
        );

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("未找到");
    }

    @Test
    @DisplayName("applyModification: 文件不存在时返回 fail")
    void applyModificationFileMissing() {
        var result = fileService.applyModification(
                "/nonexistent/file.xml",
                "old",
                "new"
        );
        assertThat(result.success()).isFalse();
    }
}
