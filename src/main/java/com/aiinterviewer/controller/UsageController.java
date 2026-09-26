package com.aiinterviewer.controller;

import com.aiinterviewer.common.ApiResponse;
import com.aiinterviewer.dto.SessionUsage;
import com.aiinterviewer.infra.web.AuthInterceptor;
import com.aiinterviewer.service.UsageService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 成本报表 API（FR-18，阶段 6）：按会话聚合 token 消耗（消息 + 报告）。
 */
@RestController
@RequestMapping("/api/usage")
@RequiredArgsConstructor
public class UsageController {

    private final UsageService usageService;

    @GetMapping
    public ApiResponse<List<SessionUsage>> usage(HttpServletRequest request) {
        return ApiResponse.ok(usageService.byUser(AuthInterceptor.currentUserId(request)));
    }
}
