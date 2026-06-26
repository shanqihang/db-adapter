package com.dbadapter.controller;

import com.dbadapter.dto.Dto;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * API Controller 集成测试（使用 H2 内存库，不依赖 claude CLI）
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional  // 每个测试后回滚，保证隔离
class ApiControllerTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    // ==================== /api/status ====================

    @Test
    @DisplayName("GET /api/status 返回 ok:true")
    void statusEndpoint() throws Exception {
        mvc.perform(get("/api/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.activeProcesses").isNumber());
    }

    // ==================== 会话 CRUD ====================

    @Test
    @DisplayName("POST /api/sessions 创建会话")
    void createSession() throws Exception {
        Dto.SessionCreateReq req = new Dto.SessionCreateReq();
        req.setName("测试会话");
        req.setDbType("dm");
        req.setProjectPath("/tmp/test-project");

        MvcResult result = mvc.perform(post("/api/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.name").value("测试会话"))
                .andExpect(jsonPath("$.dbType").value("dm"))
                .andReturn();

        String id = json.readTree(result.getResponse().getContentAsString()).get("id").asText();
        assertThat(id).isNotBlank();
    }

    @Test
    @DisplayName("POST /api/sessions 名称为空返回 400")
    void createSessionEmptyName() throws Exception {
        Dto.SessionCreateReq req = new Dto.SessionCreateReq();
        req.setName("  ");

        mvc.perform(post("/api/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").isNotEmpty());
    }

    @Test
    @DisplayName("GET /api/sessions 返回列表")
    void listSessions() throws Exception {
        // 先创建一个
        Dto.SessionCreateReq req = new Dto.SessionCreateReq();
        req.setName("List 测试");
        mvc.perform(post("/api/sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(req)));

        mvc.perform(get("/api/sessions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    @DisplayName("GET /api/sessions/{id} 不存在返回 404")
    void getSessionNotFound() throws Exception {
        mvc.perform(get("/api/sessions/nonexistent-id-xyz"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("PUT /api/sessions/{id} 更新配置")
    void updateSession() throws Exception {
        // 创建
        Dto.SessionCreateReq create = new Dto.SessionCreateReq();
        create.setName("更新测试");
        MvcResult created = mvc.perform(post("/api/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(create)))
                .andReturn();
        String id = json.readTree(created.getResponse().getContentAsString()).get("id").asText();

        // 更新
        Dto.SessionUpdateReq update = new Dto.SessionUpdateReq();
        update.setDbType("kingbase_v8r6");
        update.setDbHost("192.168.1.100");
        update.setDbPort("54321");
        update.setProjectPath("/home/user/myproject");

        mvc.perform(put("/api/sessions/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(update)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dbType").value("kingbase_v8r6"))
                .andExpect(jsonPath("$.dbHost").value("192.168.1.100"));
    }

    @Test
    @DisplayName("DELETE /api/sessions/{id} 删除会话及关联数据")
    void deleteSession() throws Exception {
        Dto.SessionCreateReq req = new Dto.SessionCreateReq();
        req.setName("待删除");
        MvcResult created = mvc.perform(post("/api/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(req)))
                .andReturn();
        String id = json.readTree(created.getResponse().getContentAsString()).get("id").asText();

        mvc.perform(delete("/api/sessions/" + id))
                .andExpect(status().isOk());

        mvc.perform(get("/api/sessions/" + id))
                .andExpect(status().isNotFound());
    }

    // ==================== 消息 ====================

    @Test
    @DisplayName("GET /api/sessions/{id}/messages 初始为空")
    void getMessagesEmpty() throws Exception {
        Dto.SessionCreateReq req = new Dto.SessionCreateReq();
        req.setName("消息测试");
        MvcResult created = mvc.perform(post("/api/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(req)))
                .andReturn();
        String id = json.readTree(created.getResponse().getContentAsString()).get("id").asText();

        mvc.perform(get("/api/sessions/" + id + "/messages"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$").isEmpty());
    }

    // ==================== Diff ====================

    @Test
    @DisplayName("GET /api/sessions/{id}/diffs 初始为空")
    void getDiffsEmpty() throws Exception {
        Dto.SessionCreateReq req = new Dto.SessionCreateReq();
        req.setName("Diff 测试");
        MvcResult created = mvc.perform(post("/api/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(req)))
                .andReturn();
        String id = json.readTree(created.getResponse().getContentAsString()).get("id").asText();

        mvc.perform(get("/api/sessions/" + id + "/diffs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    @DisplayName("POST /api/diffs/{id}/apply 不存在返回 404")
    void applyNonexistentDiff() throws Exception {
        mvc.perform(post("/api/diffs/nonexistent-diff-id/apply")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound());
    }

    // ==================== 扫描 ====================

    @Test
    @DisplayName("POST /api/sessions/{id}/scan 未配置路径返回 400")
    void scanWithoutProjectPath() throws Exception {
        Dto.SessionCreateReq req = new Dto.SessionCreateReq();
        req.setName("扫描测试");
        MvcResult created = mvc.perform(post("/api/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(req)))
                .andReturn();
        String id = json.readTree(created.getResponse().getContentAsString()).get("id").asText();

        mvc.perform(post("/api/sessions/" + id + "/scan")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").isNotEmpty());
    }

    @Test
    @DisplayName("POST /api/sessions/{id}/scan 路径不存在返回 400")
    void scanNonexistentPath() throws Exception {
        Dto.SessionCreateReq req = new Dto.SessionCreateReq();
        req.setName("路径测试");
        req.setProjectPath("/nonexistent/path/xyz/abc");
        MvcResult created = mvc.perform(post("/api/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(req)))
                .andReturn();
        String id = json.readTree(created.getResponse().getContentAsString()).get("id").asText();

        mvc.perform(post("/api/sessions/" + id + "/scan")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
    }

    // ==================== 文件读取 ====================

    @Test
    @DisplayName("POST /api/read-file 缺少 filePath 返回 400")
    void readFileNoPath() throws Exception {
        mvc.perform(post("/api/read-file")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /api/read-file 文件不存在返回 400")
    void readFileNotExist() throws Exception {
        mvc.perform(post("/api/read-file")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"filePath\":\"/no/such/file.xml\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").isNotEmpty());
    }
}
