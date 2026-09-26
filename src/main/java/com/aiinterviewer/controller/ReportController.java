package com.aiinterviewer.controller;

import com.aiinterviewer.common.ApiResponse;
import com.aiinterviewer.dto.ReportResponse;
import com.aiinterviewer.infra.web.AuthInterceptor;
import com.aiinterviewer.service.ReportService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 评估报告 API（FR-13/14/15）。归属校验沿用 404 惯例（ReportService 内部处理）。
 */
@RestController
@RequestMapping("/api/sessions/{sessionId}")
@RequiredArgsConstructor
public class ReportController {

    private final ReportService reportService;

    @GetMapping("/report")
    public ApiResponse<ReportResponse> get(@PathVariable long sessionId, HttpServletRequest request) {
        return ApiResponse.ok(reportService.get(sessionId, AuthInterceptor.currentUserId(request)));
    }

    /** 死信补偿：FAILED 或消息丢失时重新入队（FR-15） */
    @PostMapping("/report/retry")
    public ApiResponse<ReportResponse> retry(@PathVariable long sessionId, HttpServletRequest request) {
        return ApiResponse.ok(reportService.retry(sessionId, AuthInterceptor.currentUserId(request)));
    }
}
