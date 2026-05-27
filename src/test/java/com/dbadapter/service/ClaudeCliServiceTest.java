package com.dbadapter.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ClaudeCliService 单元测试
 *
 * 注意：不直接调用真实 claude CLI（CI 环境未必安装），
 * 而是测试流程控制、超时、JSON 解析等逻辑。
 */
class ClaudeCliServiceTest {

    private ClaudeCliService service;

    @BeforeEach
    void setUp() {
        service = new ClaudeCliService(new ObjectMapper());
        // 使用 echo 命令模拟 claude（输出一行 stream-json）
        ReflectionTestUtils.setField(service, "timeoutMs", 10_000L);
        ReflectionTestUtils.setField(service, "maxConcurrent", 3);
        ReflectionTestUtils.setField(service, "allowFileOps", false);
        ReflectionTestUtils.setField(service, "skillDir", "./skills-nonexistent");
    }

    @Test
    @DisplayName("isCliAvailable: 当 claude 不存在时应返回 false")
    void testCliNotAvailable() {
        // 设置一个不存在的命令
        ReflectionTestUtils.setField(service, "cliPath", "claude-command-that-does-not-exist-xyz");
        assertThat(service.isCliAvailable()).isFalse();
    }

    @Test
    @DisplayName("isCliAvailable: echo 命令始终可用（模拟 CLI 存在的情况）")
    void testEchoAvailable() {
        ReflectionTestUtils.setField(service, "cliPath", "echo");
        // echo --version 不会报错，exit 0
        assertThat(service.isCliAvailable()).isTrue();
    }

    @Test
    @DisplayName("invokeStreaming: 使用 echo 模拟输出，验证 onDone 被调用")
    void testStreamingWithEcho() throws InterruptedException {
        ReflectionTestUtils.setField(service, "cliPath", "echo");

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> result = new AtomicReference<>();
        AtomicReference<String> error = new AtomicReference<>();

        // echo 会把参数直接输出（非 JSON，走 fallback 路径返回原文）
        service.invokeStreaming(
                "hello-test",
                null,
                null,
                chunk -> { /* 收集 chunk */ },
                fullText -> {
                    result.set(fullText);
                    latch.countDown();
                },
                err -> {
                    error.set(err);
                    latch.countDown();
                }
        );

        boolean finished = latch.await(15, TimeUnit.SECONDS);
        assertThat(finished).isTrue();
        assertThat(error.get()).isNull();
        // echo 输出应包含 prompt 文本
        assertThat(result.get()).isNotNull();
    }

    @Test
    @DisplayName("invokeStreaming: 并发控制 - 超过上限时应快速返回 error")
    void testConcurrencyLimit() throws InterruptedException {
        ReflectionTestUtils.setField(service, "maxConcurrent", 0); // 设为 0，任何调用都超限

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> error = new AtomicReference<>();

        service.invokeStreaming(
                "test",
                null,
                null,
                chunk -> {},
                done -> latch.countDown(),
                err -> { error.set(err); latch.countDown(); }
        );

        latch.await(3, TimeUnit.SECONDS);
        assertThat(error.get()).contains("繁忙");
    }

    @Test
    @DisplayName("activeProcessCount 初始为 0")
    void testInitialActiveCount() {
        assertThat(service.getActiveProcessCount()).isEqualTo(0);
    }
}
