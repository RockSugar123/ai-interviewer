package com.aiinterviewer.service;

import com.aiinterviewer.common.BusinessException;
import com.aiinterviewer.common.ErrorCode;
import com.aiinterviewer.dto.CreateSessionRequest;
import com.aiinterviewer.dto.PageResponse;
import com.aiinterviewer.dto.SessionDetailResponse;
import com.aiinterviewer.infra.persistence.entity.InterviewSession;
import com.aiinterviewer.infra.persistence.mapper.InterviewSessionMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class SessionService {

    private static final long MAX_PAGE_SIZE = 50;

    private final InterviewSessionMapper sessionMapper;
    private final ResumeService resumeService;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public SessionDetailResponse create(long userId, CreateSessionRequest req) {
        // 简历挂载：归属校验（非本人简历按不存在处理），未传则置空
        Long resumeFileId = req.resumeFileId() == null ? null
                : resumeService.getOwned(req.resumeFileId(), userId).getId();
        InterviewSession session = new InterviewSession();
        session.setUserId(userId);
        session.setTitle(req.title() == null || req.title().isBlank() ? "未命名面试" : req.title());
        session.setJdText(req.jdText());
        session.setResumeFileId(resumeFileId);
        session.setStatus(InterviewSession.STATUS_CREATED);
        sessionMapper.insert(session);
        log.info("会话创建 [sessionId={} userId={} resumeFileId={}]", session.getId(), userId, resumeFileId);
        return toDetail(session);
    }

    public PageResponse<SessionDetailResponse> page(long userId, long page, long size) {
        Page<InterviewSession> result = sessionMapper.selectPage(
                new Page<>(page, Math.min(size, MAX_PAGE_SIZE)),
                new LambdaQueryWrapper<InterviewSession>()
                        .eq(InterviewSession::getUserId, userId)
                        .orderByDesc(InterviewSession::getUpdatedAt));
        List<SessionDetailResponse> list = result.getRecords().stream().map(this::toDetail).toList();
        return new PageResponse<>(result.getTotal(), page, size, list);
    }

    public SessionDetailResponse detail(long sessionId, long userId) {
        return toDetail(getOwned(sessionId, userId));
    }

    @Transactional
    public void delete(long sessionId, long userId) {
        getOwned(sessionId, userId);
        sessionMapper.deleteById(sessionId); // 逻辑删除
        log.info("会话删除 [sessionId={} userId={}]", sessionId, userId);
    }

    /** 显式结束面试（Agent 决策收尾也会走同一状态）。事务提交后发结束事件 → ReportService 发 MQ 生成报告 */
    @Transactional
    public SessionDetailResponse finish(long sessionId, long userId) {
        getOwned(sessionId, userId);
        InterviewSession update = new InterviewSession();
        update.setId(sessionId);
        update.setStatus(InterviewSession.STATUS_FINISHED);
        update.setAgentState(InterviewSession.AGENT_DONE);
        sessionMapper.updateById(update);
        eventPublisher.publishEvent(new InterviewFinishedEvent(sessionId, userId, System.currentTimeMillis()));
        log.info("会话结束 [sessionId={} userId={}]", sessionId, userId);
        return detail(sessionId, userId);
    }

    /** 归属校验：非本人资源统一按不存在处理，不泄露会话存在性 */
    public InterviewSession getOwned(long sessionId, long userId) {
        InterviewSession session = sessionMapper.selectById(sessionId);
        if (session == null || !session.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "会话不存在");
        }
        return session;
    }

    private SessionDetailResponse toDetail(InterviewSession s) {
        return new SessionDetailResponse(s.getId(), s.getTitle(), s.getJdText(),
                s.getStatus(), s.getAgentState(), s.getProbeCount(), s.getQuestionCount(),
                s.getResumeFileId(), s.getCreatedAt(), s.getUpdatedAt());
    }
}
