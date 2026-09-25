package com.aiinterviewer.controller;

import com.aiinterviewer.agent.InterviewAgent;
import com.aiinterviewer.common.ApiResponse;
import com.aiinterviewer.dto.MessageResponse;
import com.aiinterviewer.dto.SendMessageRequest;
import com.aiinterviewer.infra.web.AuthInterceptor;
import com.aiinterviewer.service.ChatContextService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/sessions/{sessionId}/messages")
@RequiredArgsConstructor
public class MessageController {

    private final ChatContextService chatContextService;
    private final InterviewAgent interviewAgent;

    /**
     * 发言并获取面试官回复（同步；阶段 3 改为 SSE 流式）。
     * 用户消息先落库，Agent 从上下文读取后生成回复。
     */
    @PostMapping
    public ApiResponse<MessageResponse> send(@PathVariable Long sessionId,
                                             @Valid @RequestBody SendMessageRequest req,
                                             HttpServletRequest request) {
        long userId = AuthInterceptor.currentUserId(request);
        chatContextService.append(sessionId, userId, req.content());
        return ApiResponse.ok(interviewAgent.reply(sessionId, userId));
    }

    @GetMapping
    public ApiResponse<List<MessageResponse>> list(@PathVariable Long sessionId,
                                                   @RequestParam(required = false) Integer limit,
                                                   @RequestParam(required = false) Long afterSeq,
                                                   HttpServletRequest request) {
        return ApiResponse.ok(chatContextService.list(
                sessionId, AuthInterceptor.currentUserId(request), limit, afterSeq));
    }
}
