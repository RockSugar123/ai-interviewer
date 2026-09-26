package com.aiinterviewer.service;

import com.aiinterviewer.common.BusinessException;
import com.aiinterviewer.common.ErrorCode;
import com.aiinterviewer.dto.MessageResponse;
import com.aiinterviewer.infra.persistence.entity.InterviewMessage;
import com.aiinterviewer.infra.persistence.entity.InterviewSession;
import com.aiinterviewer.infra.persistence.mapper.InterviewMessageMapper;
import com.aiinterviewer.infra.persistence.mapper.InterviewSessionMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 消息持久化 + 热会话缓存（FR-2/FR-3 基础）。
 *
 * 写路径：MySQL 事务提交后再写 Redis（防回滚脏缓存）；Redis 任何异常只 warn 不阻断。
 * 读路径：Redis 优先（最近 N 条），未命中读库回填；Redis 不可用直接读库。
 * 主链路依赖只有 MySQL——Redis 挂掉只影响缓存效果，不影响正确性。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatContextService {

    private static final String KEY_PREFIX = "session:ctx:";
    /** 热缓存保留的最近消息条数（上下文窗口的基础，阶段 2 裁剪策略在此之上） */
    private static final int CONTEXT_WINDOW = 50;
    private static final int MAX_LIMIT = 200;
    private static final Duration TTL = Duration.ofHours(24);

    private final SessionService sessionService;
    private final InterviewMessageMapper messageMapper;
    private final InterviewSessionMapper sessionMapper;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    /** 用户发言：role 固定 USER，token 粗估（阶段 2 起以 LLM usage 为准） */
    public MessageResponse append(long sessionId, long userId, String content) {
        InterviewSession session = sessionService.getOwned(sessionId, userId);
        if (InterviewSession.STATUS_FINISHED.equals(session.getStatus())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "会话已结束，不能再发言");
        }
        return persistMessage(sessionId, InterviewMessage.ROLE_USER, content, content.length(), null);
    }

    /** 阶段 2 Agent 写入 ASSISTANT 消息的入口（tokenCount 取 LLM 响应 usage；citations 为 RAG 引用，可空） */
    public MessageResponse writeAssistant(long sessionId, String content, int tokenCount,
                                          List<com.aiinterviewer.dto.Citation> citations) {
        return persistMessage(sessionId, InterviewMessage.ROLE_ASSISTANT, content, tokenCount, citations);
    }

    /**
     * 落库 + 刷新会话活跃时间（同一事务），随后尽力写缓存。
     * seq 取 max+1，并发竞争由 uk_session_seq 唯一键兜底。
     */
    private MessageResponse persistMessage(long sessionId, String role, String content, int tokenCount,
                                           List<com.aiinterviewer.dto.Citation> citations) {
        InterviewMessage message = transactionTemplate.execute(status -> {
            long nextSeq = messageMapper.selectMaxSeq(sessionId) + 1;
            InterviewMessage m = new InterviewMessage();
            m.setSessionId(sessionId);
            m.setSeq(nextSeq);
            m.setRole(role);
            m.setContent(content);
            m.setTokenCount(tokenCount);
            if (citations != null && !citations.isEmpty()) {
                try {
                    m.setCitations(objectMapper.writeValueAsString(citations));
                } catch (JsonProcessingException e) {
                    // 引用属增强信息，序列化失败不阻断消息落库
                    log.warn("引用序列化失败，忽略 [sessionId={}]", sessionId, e);
                }
            }
            messageMapper.insert(m);

            // 状态推进 CREATED -> IN_PROGRESS，同时刷新 updated_at（列表按最近活跃排序）
            InterviewSession bump = new InterviewSession();
            bump.setId(sessionId);
            bump.setStatus(InterviewSession.STATUS_IN_PROGRESS);
            bump.setUpdatedAt(LocalDateTime.now());
            sessionMapper.updateById(bump);
            return m;
        });
        cacheAppend(message);
        log.debug("消息落库 [sessionId={} seq={} role={}]", sessionId, message.getSeq(), role);
        return toResponse(message);
    }

    /**
     * 消息列表。
     * afterSeq 非空 = 增量拉取（W3 SSE 断线续传复用此路径，直接读库保证序号语义准确）；
     * 否则取最近 limit 条：Redis 优先，未命中读库回填，Redis 不可用降级读库。
     */
    public List<MessageResponse> list(long sessionId, long userId, Integer limit, Long afterSeq) {
        sessionService.getOwned(sessionId, userId);
        int effectiveLimit = limit == null ? CONTEXT_WINDOW : Math.min(Math.max(limit, 1), MAX_LIMIT);

        if (afterSeq != null) {
            return loadFromDb(sessionId, effectiveLimit, afterSeq);
        }

        String key = KEY_PREFIX + sessionId;
        List<String> raw;
        try {
            raw = redis.opsForList().range(key, -effectiveLimit, -1);
        } catch (DataAccessException e) {
            log.warn("Redis 读取失败，降级读库 [sessionId={}]", sessionId, e);
            return loadFromDb(sessionId, effectiveLimit, null);
        }
        if (raw == null || raw.isEmpty()) {
            List<MessageResponse> fromDb = loadFromDb(sessionId, effectiveLimit, null);
            warmCache(sessionId, fromDb);
            return fromDb;
        }
        try {
            List<MessageResponse> parsed = new ArrayList<>(raw.size());
            for (String s : raw) {
                parsed.add(objectMapper.readValue(s, MessageResponse.class));
            }
            return parsed;
        } catch (JsonProcessingException e) {
            log.warn("缓存反序列化失败，降级读库 [sessionId={}]", sessionId, e);
            return loadFromDb(sessionId, effectiveLimit, null);
        }
    }

    /** 会话删除时清理热缓存（失败无害：key 随 TTL 过期） */
    public void evict(long sessionId) {
        try {
            redis.delete(KEY_PREFIX + sessionId);
        } catch (DataAccessException e) {
            log.warn("Redis 清理失败（key 将随 TTL 过期）[sessionId={}]", sessionId, e);
        }
    }

    private void cacheAppend(InterviewMessage m) {
        try {
            String key = KEY_PREFIX + m.getSessionId();
            if (cacheHasGap(key, m.getSeq())) {
                // 断档（如 Redis 宕机期间的消息只落了库）：整窗重建，保证缓存 = 最近 N 条的精确窗口
                rebuildWindow(key, m.getSessionId());
            } else {
                redis.opsForList().rightPush(key, objectMapper.writeValueAsString(toResponse(m)));
            }
            redis.opsForList().trim(key, -CONTEXT_WINDOW, -1);
            redis.expire(key, TTL);
        } catch (DataAccessException e) {
            log.warn("Redis 写入失败，本次上下文仅落库 [sessionId={}]", m.getSessionId(), e);
        } catch (JsonProcessingException e) {
            log.warn("上下文缓存序列化失败 [sessionId={}]", m.getSessionId(), e);
        }
    }

    /**
     * 断档检测：缓存首条序号 != 窗口应有的首条序号（窗口=[seq-49, seq]，不足 50 条时从 1 起）。
     * 空缓存且会话已有历史同样视为断档。
     */
    private boolean cacheHasGap(String key, long seq) throws DataAccessException, JsonProcessingException {
        List<String> head = redis.opsForList().range(key, 0, 0);
        long expectedOldest = Math.max(1, seq - CONTEXT_WINDOW + 1);
        if (head == null || head.isEmpty()) {
            return seq > 1;
        }
        MessageResponse oldest = objectMapper.readValue(head.get(0), MessageResponse.class);
        return oldest.seq() != expectedOldest;
    }

    /** 删除并按库中最近 CONTEXT_WINDOW 条重建缓存窗口（库为唯一事实来源） */
    private void rebuildWindow(String key, long sessionId) throws JsonProcessingException {
        redis.delete(key);
        List<MessageResponse> window = loadFromDb(sessionId, CONTEXT_WINDOW, null);
        for (MessageResponse r : window) {
            redis.opsForList().rightPush(key, objectMapper.writeValueAsString(r));
        }
        log.info("上下文缓存窗口重建 [sessionId={} size={}]", sessionId, window.size());
    }

    private void warmCache(long sessionId, List<MessageResponse> messages) {
        if (messages.isEmpty()) {
            return;
        }
        try {
            String key = KEY_PREFIX + sessionId;
            for (MessageResponse m : messages) {
                redis.opsForList().rightPush(key, objectMapper.writeValueAsString(m));
            }
            redis.opsForList().trim(key, -CONTEXT_WINDOW, -1);
            redis.expire(key, TTL);
        } catch (DataAccessException e) {
            log.warn("上下文缓存回填失败 [sessionId={}]", sessionId, e);
        } catch (JsonProcessingException e) {
            log.warn("上下文缓存回填序列化失败 [sessionId={}]", sessionId, e);
        }
    }

    /** 按序号升序取最近 limit 条（afterSeq 非空时取其后的增量） */
    private List<MessageResponse> loadFromDb(long sessionId, int limit, Long afterSeq) {
        LambdaQueryWrapper<InterviewMessage> qw = new LambdaQueryWrapper<InterviewMessage>()
                .eq(InterviewMessage::getSessionId, sessionId)
                .gt(afterSeq != null, InterviewMessage::getSeq, afterSeq)
                .orderByDesc(InterviewMessage::getSeq)
                .last("LIMIT " + limit);
        List<InterviewMessage> rows = messageMapper.selectList(qw);
        Collections.reverse(rows);
        return rows.stream().map(this::toResponse).toList();
    }

    private MessageResponse toResponse(InterviewMessage m) {
        List<com.aiinterviewer.dto.Citation> citations = null;
        if (m.getCitations() != null && !m.getCitations().isBlank()) {
            try {
                citations = objectMapper.readValue(m.getCitations(), objectMapper.getTypeFactory()
                        .constructCollectionType(List.class, com.aiinterviewer.dto.Citation.class));
            } catch (JsonProcessingException e) {
                log.warn("引用反序列化失败，忽略 [seq={}]", m.getSeq(), e);
            }
        }
        return new MessageResponse(m.getSeq(), m.getRole(), m.getContent(),
                m.getTokenCount(), m.getCreatedAt(), citations);
    }
}
