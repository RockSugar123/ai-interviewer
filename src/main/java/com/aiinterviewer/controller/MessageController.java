package com.aiinterviewer.controller;

import com.aiinterviewer.agent.InterviewAgent;
import com.aiinterviewer.agent.InterviewAgentProperties;
import com.aiinterviewer.common.ApiResponse;
import com.aiinterviewer.dto.MessageResponse;
import com.aiinterviewer.dto.SendMessageRequest;
import com.aiinterviewer.infra.web.AuthInterceptor;
import com.aiinterviewer.service.ChatContextService;
import com.aiinterviewer.service.InterviewStreamService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

@RestController
@RequestMapping("/api/sessions/{sessionId}/messages")
@RequiredArgsConstructor
public class MessageController {

    private final ChatContextService chatContextService;
    private final InterviewAgent interviewAgent;
    private final InterviewStreamService streamService;
    private final InterviewAgentProperties agentProperties;

    /**
     * 发言（W3 起异步流式）：用户消息落库并触发异步生成后立即返回该条用户消息；
     * 面试官回复经 GET /stream（SSE）推送。stream-enabled=false 时回退同步整段路径。
     */
    @PostMapping
    public ApiResponse<MessageResponse> send(@PathVariable Long sessionId,
                                             @Valid @RequestBody SendMessageRequest req,
                                             HttpServletRequest request) {
        long userId = AuthInterceptor.currentUserId(request);
        MessageResponse userMsg = chatContextService.append(sessionId, userId, req.content());
        if (!agentProperties.streamEnabled()) {
            return ApiResponse.ok(interviewAgent.reply(sessionId, userId));
        }
        streamService.startGeneration(sessionId, userId, userMsg);
        return ApiResponse.ok(userMsg);
    }

    /** 订阅面试官回复的 SSE 流。afterSeq = 客户端收到的最后一个事件 id（用户消息 seq），断线重连续传用。 */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable Long sessionId,
                             @RequestParam(name = "afterSeq", required = false) Long afterSeq,
                             HttpServletRequest request) {
        return streamService.subscribe(sessionId, AuthInterceptor.currentUserId(request), afterSeq);
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
