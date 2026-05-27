package com.dbadapter.controller;

import com.dbadapter.dto.Dto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Dto.ErrResp> handleBadArg(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(new Dto.ErrResp(e.getMessage()));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Dto.ErrResp> handleNotFound(NoResourceFoundException e) {
        // 静态资源 404 不打日志，其他打 warn
        if (!e.getMessage().contains("favicon")) {
            log.warn("资源未找到: {}", e.getMessage());
        }
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new Dto.ErrResp("资源不存在: " + e.getResourcePath()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Dto.ErrResp> handleGeneral(Exception e) {
        log.error("未处理异常", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new Dto.ErrResp("服务器内部错误: " + e.getMessage()));
    }
}
