package com.aiinterviewer.controller;

import com.aiinterviewer.common.ApiResponse;
import com.aiinterviewer.dto.CreateSessionRequest;
import com.aiinterviewer.dto.PageResponse;
import com.aiinterviewer.dto.SessionDetailResponse;
import com.aiinterviewer.infra.web.AuthInterceptor;
import com.aiinterviewer.service.ChatContextService;
import com.aiinterviewer.service.SessionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/sessions")
@RequiredArgsConstructor
public class SessionController {

    private final SessionService sessionService;
    private final ChatContextService chatContextService;

    @PostMapping
    public ApiResponse<SessionDetailResponse> create(@Valid @RequestBody CreateSessionRequest req,
                                                     HttpServletRequest request) {
        return ApiResponse.ok(sessionService.create(AuthInterceptor.currentUserId(request), req));
    }

    @GetMapping
    public ApiResponse<PageResponse<SessionDetailResponse>> page(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "10") long size,
            HttpServletRequest request) {
        return ApiResponse.ok(sessionService.page(AuthInterceptor.currentUserId(request), page, size));
    }

    @GetMapping("/{id}")
    public ApiResponse<SessionDetailResponse> detail(@PathVariable Long id, HttpServletRequest request) {
        return ApiResponse.ok(sessionService.detail(id, AuthInterceptor.currentUserId(request)));
    }

    @PostMapping("/{id}/finish")
    public ApiResponse<SessionDetailResponse> finish(@PathVariable Long id, HttpServletRequest request) {
        return ApiResponse.ok(sessionService.finish(id, AuthInterceptor.currentUserId(request)));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id, HttpServletRequest request) {
        long userId = AuthInterceptor.currentUserId(request);
        sessionService.delete(id, userId);
        chatContextService.evict(id);
        return ApiResponse.ok();
    }
}
