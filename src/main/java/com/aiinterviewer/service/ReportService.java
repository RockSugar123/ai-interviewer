package com.aiinterviewer.service;

import com.aiinterviewer.common.BusinessException;
import com.aiinterviewer.common.ErrorCode;
import com.aiinterviewer.dto.MessageResponse;
import com.aiinterviewer.dto.ReportResponse;
import com.aiinterviewer.infra.persistence.entity.InterviewMessage;
import com.aiinterviewer.infra.persistence.entity.InterviewReport;
import com.aiinterviewer.infra.persistence.mapper.InterviewReportMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import org.apache.rocketmq.spring.core.RocketMQTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 评估报告（FR-13/14/15，阶段 5）：
 * 生产侧：监听会话结束事件 → AFTER_COMMIT 发 MQ（本地事务提交后才发，防"事务回滚但消息已发"）。
 * 消费侧：聚合会话全量消息 → LLM 生成四维 Markdown 报告 → 落库。
 *
 * FR-15 三件套：
 * - 幂等：Redis setnx(sessionId+finishedAt, 30min) 挡重复投递 + interview_report.uk_session 唯一键兜底；
 * - 重试：消费失败释放幂等锁并抛异常 → RocketMQ 指数退避重试（默认 16 次）；
 * - 死信：重试耗尽自动进 %DLQ%report-consumer-group，报告置 FAILED（error_msg 可见），retry 接口补偿重新入队。
 */
@Slf4j
@Service
public class ReportService {

    /** 生成输入的对话字符上限：超长从头截断，保留尾部最新对话（报告最看重后半场深挖） */
    private static final int MAX_TRANSCRIPT_CHARS = 30000;
    private static final Duration DEDUP_TTL = Duration.ofMinutes(30);

    private final ChatClient chatClient;
    private final RocketMQTemplate rocketMQTemplate;
    private final MqProperties mqProps;
    private final InterviewReportMapper reportMapper;
    private final SessionService sessionService;
    private final ChatContextService chatContextService;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final com.aiinterviewer.infra.limiter.LlmRateLimiter rateLimiter;

    public ReportService(ChatClient.Builder chatClientBuilder, RocketMQTemplate rocketMQTemplate,
                         MqProperties mqProps, InterviewReportMapper reportMapper,
                         SessionService sessionService, ChatContextService chatContextService,
                         StringRedisTemplate redis, ObjectMapper objectMapper,
                         com.aiinterviewer.infra.limiter.LlmRateLimiter rateLimiter) {
        this.chatClient = chatClientBuilder.build();
        this.rocketMQTemplate = rocketMQTemplate;
        this.mqProps = mqProps;
        this.reportMapper = reportMapper;
        this.sessionService = sessionService;
        this.chatContextService = chatContextService;
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.rateLimiter = rateLimiter;
    }

    // ---------- 生产侧：会话结束事件 → MQ ----------

    /** finish 接口有事务 → AFTER_COMMIT 后发；Agent 侧无事务 → fallbackExecution 立即发 */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onInterviewFinished(InterviewFinishedEvent event) {
        if (!mqProps.enabled()) {
            log.info("[Report] MQ 未开启，跳过报告生成 [sessionId={}]", event.sessionId());
            return;
        }
        sendReportMessage(event.sessionId(), event.userId(), event.finishedAt());
    }

    /** 补偿重试（FR-15 死信配套）：FAILED 或无报告时重新入队；新 finishedAt → 新幂等键 */
    public ReportResponse retry(long sessionId, long userId) {
        if (!mqProps.enabled()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "MQ 未开启，报告功能停用");
        }
        sessionService.getOwned(sessionId, userId);
        long finishedAt = System.currentTimeMillis();
        sendReportMessage(sessionId, userId, finishedAt);
        upsertPending(sessionId, userId);
        log.info("[Report] 手动补偿重试 [sessionId={} userId={}]", sessionId, userId);
        return get(sessionId, userId);
    }

    private void sendReportMessage(long sessionId, long userId, long finishedAt) {
        ReportMessage msg = new ReportMessage(sessionId, userId, finishedAt);
        try {
            rocketMQTemplate.syncSend(mqProps.reportTopic(),
                    MessageBuilder.withPayload(msg)
                            .setHeader("KEYS", "report:" + sessionId + ":" + finishedAt)
                            .build());
            log.info("[Report] 报告消息已发送 [sessionId={} finishedAt={}]", sessionId, finishedAt);
        } catch (Exception e) {
            // AFTER_COMMIT 内异常不影响接口响应；发送失败 = 不会投递 → 落 FAILED 让 retry 接口可发现
            log.error("[Report] 报告消息发送失败 [sessionId={}]", sessionId, e);
            upsertFailed(sessionId, userId, "消息发送失败: " + e.getMessage());
        }
    }

    // ---------- 消费侧：MQ → 报告落库 ----------

    public void handleReportMessage(ReportMessage msg) {
        if (!mqProps.enabled()) {
            log.warn("[Report] MQ 未开启，忽略存量消息 {}", msg);
            return;
        }
        String dedupKey = "report:dedup:" + msg.sessionId() + ":" + msg.finishedAt();
        Boolean first = redis.opsForValue().setIfAbsent(dedupKey, "1", DEDUP_TTL);
        if (!Boolean.TRUE.equals(first)) {
            log.info("[Report] 重复投递，跳过 [dedup={}]", dedupKey);
            return;
        }
        InterviewReport pending = upsertPending(msg.sessionId(), msg.userId());
        try {
            generateAndSave(msg.sessionId(), msg.userId(), pending.getId());
        } catch (Exception e) {
            redis.delete(dedupKey); // 放行 MQ 重试（否则重试投递会被幂等锁挡掉）
            String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            upsertFailed(msg.sessionId(), msg.userId(), reason);
            throw new IllegalStateException("报告生成失败（将由 MQ 退避重试）: " + reason, e);
        }
    }

    private void generateAndSave(long sessionId, long userId, Long reportId) {
        var session = sessionService.getOwned(sessionId, userId);
        String transcript = buildFullTranscript(sessionId, userId);
        if (transcript.isBlank()) {
            throw new IllegalStateException("会话无对话内容，无法生成报告");
        }
        markRunning(reportId);

        ChatClientResponse ccr = chatClient.prompt()
                .system(ReportPrompts.REPORT_SYSTEM)
                .user(ReportPrompts.reportInput(session.getJdText(), transcript))
                .options(OpenAiChatOptions.builder().temperature(0.3).build())
                .call()
                .chatClientResponse();
        ChatResponse chatResponse = ccr.chatResponse();
        String content = chatResponse.getResult().getOutput().getText();
        if (content == null || content.isBlank()) {
            throw new IllegalStateException("模型返回空报告");
        }
        int tokens = 0;
        String model = null;
        if (chatResponse.getMetadata() != null) {
            if (chatResponse.getMetadata().getUsage() != null) {
                tokens = chatResponse.getMetadata().getUsage().getTotalTokens().intValue();
            }
            model = chatResponse.getMetadata().getModel();
        }
        InterviewReport done = new InterviewReport();
        done.setId(reportId);
        done.setStatus(InterviewReport.STATUS_DONE);
        done.setContent(content);
        done.setModel(model);
        done.setTokenUsed(tokens);
        done.setErrorMsg("");
        reportMapper.updateById(done);
        rateLimiter.recordTokens(tokens); // FR-16：报告生成 token 记入全局日配额
        log.info("[Report] 报告生成完成 [sessionId={} tokens={} chars={}]", sessionId, tokens, content.length());
    }

    /** 全量对话（不裁剪到会话窗口）：报告依赖完整上下文；超长保护保尾部 */
    private String buildFullTranscript(long sessionId, long userId) {
        List<MessageResponse> messages = chatContextService.list(sessionId, userId, null, null);
        StringBuilder sb = new StringBuilder();
        for (MessageResponse m : messages) {
            sb.append(InterviewMessage.ROLE_ASSISTANT.equals(m.role()) ? "面试官" : "候选人")
                    .append(": ").append(m.content()).append('\n');
        }
        String s = sb.toString();
        return s.length() > MAX_TRANSCRIPT_CHARS ? s.substring(s.length() - MAX_TRANSCRIPT_CHARS) : s;
    }

    // ---------- 报告记录状态机（uk_session 兜底幂等：并发插入冲突转 UPDATE） ----------

    private InterviewReport upsertPending(long sessionId, long userId) {
        InterviewReport existing = selectBySession(sessionId);
        if (existing == null) {
            InterviewReport report = new InterviewReport();
            report.setSessionId(sessionId);
            report.setUserId(userId);
            report.setStatus(InterviewReport.STATUS_PENDING);
            try {
                reportMapper.insert(report);
                return report;
            } catch (DuplicateKeyException e) {
                existing = selectBySession(sessionId);
            }
        }
        InterviewReport update = new InterviewReport();
        update.setId(existing.getId());
        update.setStatus(InterviewReport.STATUS_PENDING);
        update.setErrorMsg("");
        reportMapper.updateById(update);
        return selectBySession(sessionId);
    }

    private void markRunning(Long reportId) {
        InterviewReport update = new InterviewReport();
        update.setId(reportId);
        update.setStatus(InterviewReport.STATUS_RUNNING);
        reportMapper.updateById(update);
    }

    private void upsertFailed(long sessionId, long userId, String reason) {
        InterviewReport existing = selectBySession(sessionId);
        if (existing == null) {
            InterviewReport report = new InterviewReport();
            report.setSessionId(sessionId);
            report.setUserId(userId);
            report.setStatus(InterviewReport.STATUS_FAILED);
            report.setErrorMsg(abbreviate(reason));
            try {
                reportMapper.insert(report);
                return;
            } catch (DuplicateKeyException e) {
                existing = selectBySession(sessionId);
            }
        }
        InterviewReport update = new InterviewReport();
        update.setId(existing.getId());
        update.setStatus(InterviewReport.STATUS_FAILED);
        update.setErrorMsg(abbreviate(reason));
        reportMapper.updateById(update);
    }

    // ---------- 查询 ----------

    public ReportResponse get(long sessionId, long userId) {
        sessionService.getOwned(sessionId, userId);
        InterviewReport report = selectBySession(sessionId);
        if (report == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "报告尚未生成");
        }
        return toResponse(report);
    }

    private InterviewReport selectBySession(long sessionId) {
        return reportMapper.selectOne(new LambdaQueryWrapper<InterviewReport>()
                .eq(InterviewReport::getSessionId, sessionId));
    }

    private ReportResponse toResponse(InterviewReport r) {
        return new ReportResponse(r.getId(), r.getSessionId(), r.getStatus(), r.getContent(),
                r.getModel(), r.getTokenUsed(), r.getErrorMsg(),
                r.getUpdatedAt() == null ? LocalDateTime.now() : r.getUpdatedAt());
    }

    private String abbreviate(String s) {
        return s != null && s.length() > 480 ? s.substring(0, 480) + "…" : s;
    }
}
