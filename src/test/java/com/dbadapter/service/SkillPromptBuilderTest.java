package com.dbadapter.service;

import com.dbadapter.entity.Session;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class SkillPromptBuilderTest {

    private SkillPromptBuilder builder;

    @BeforeEach
    void setUp() {
        builder = new SkillPromptBuilder();
    }

    @Test
    @DisplayName("buildContextPrompt: 包含数据库类型")
    void contextIncludesDbType() {
        Session s = session("dameng", "192.168.1.1", "5236", "testdb", "/home/user/proj");
        String prompt = builder.buildContextPrompt(s);
        assertThat(prompt).contains("达梦");
        assertThat(prompt).contains("192.168.1.1");
        assertThat(prompt).contains("5236");
        assertThat(prompt).contains("testdb");
        assertThat(prompt).contains("/home/user/proj");
    }

    @Test
    @DisplayName("buildContextPrompt: 包含 JSON 输出格式说明")
    void contextIncludesOutputFormat() {
        Session s = session("dameng", null, null, null, null);
        String prompt = builder.buildContextPrompt(s);
        assertThat(prompt).contains("modifications");
        assertThat(prompt).contains("filePath");
        assertThat(prompt).contains("original");
        assertThat(prompt).contains("modified");
    }

    @ParameterizedTest(name = "数据库类型 [{0}] 有专项规则")
    @ValueSource(strings = {"dameng", "kingbase", "gaussdb", "tidb"})
    void specificRulesExist(String dbType) {
        Session s = session(dbType, null, null, null, null);
        String prompt = builder.buildContextPrompt(s);
        // 每种数据库应有 JDBC 相关内容
        assertThat(prompt).containsAnyOf("jdbc:", "JDBC", "Driver");
    }

    @Test
    @DisplayName("buildContextPrompt: 达梦规则包含 NVL/IFNULL 转换说明")
    void damengIncludesNvlRule() {
        Session s = session("dameng", null, null, null, null);
        String prompt = builder.buildContextPrompt(s);
        assertThat(prompt).containsAnyOf("NVL", "COALESCE", "IFNULL");
    }

    @Test
    @DisplayName("buildFullSystemPrompt: 不为空且包含职责说明")
    void fullPromptNotEmpty() {
        Session s = session("kingbase", "10.0.0.1", "54321", "mydb", "/opt/proj");
        String full = builder.buildFullSystemPrompt(s);
        assertThat(full).isNotBlank();
        assertThat(full).containsAnyOf("适配", "数据库", "Java");
    }

    // ==================== 辅助方法 ====================

    private Session session(String dbType, String host, String port, String dbName, String path) {
        Session s = new Session();
        s.setDbType(dbType);
        s.setDbHost(host);
        s.setDbPort(port);
        s.setDbName(dbName);
        s.setProjectPath(path);
        return s;
    }
}
