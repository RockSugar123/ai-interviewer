package com.aiinterviewer.service;

import com.aiinterviewer.agent.InterviewAgent;
import com.aiinterviewer.common.BusinessException;
import com.aiinterviewer.common.ErrorCode;
import com.aiinterviewer.dto.MessageResponse;
import com.aiinterviewer.dto.StreamDelta;
import com.aiinterviewer.dto.StreamDone;
import com.aiinterviewer.dto.StreamError;
import com.aiinterviewer.infra.persistence.entity.InterviewSession;
import com.aiinterviewer.infra.persistence.mapper.InterviewSessionMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * SSE 流式编排（W3）：会话级生成注册表 + 订阅者广播 + 断线快照回放。
 *
 * 事件协议：delta（增量）/ full-delta（累计快照，替换语义，重连不丢不重的关键）/
 * done（终稿消息 + 状态机状态）/ error（可读失败原因）。流式期事件 id 一律为用户消息 seq，
 * 助手消息真实 seq 持久化后才可知，随 done 下发；断线期间生成已完成的场景走 afterSeq 库回放。
 *
 * 并发约定：同一 Generation 的所有 emitter 写操作（广播/快照/心跳）都在该 Generation 的锁内串行——
 * SseEmitter 底层响应流不支持并发写，且快照与后续增量必须有序，否则重连回放会出现缺口或重复。
 * 本地 1-2 个订阅者、小负载，锁内阻塞发送的代价可忽略。
 */
@Slf4j
@Service
public class InterviewStreamService {

    private static final long HEARTBEAT_INTERVAL_SECONDS = 15;

    private final InterviewAgent interviewAgent;
    private final ChatContextService chatContextService;
    private final InterviewSessionMapper sessionMapper;
    private final ThreadPoolTaskExecutor agentGenExecutor;
    private final ScheduledExecutorService heartbeatScheduler;
    private final MeterRegistry meterRegistry;
    private final long emitterTimeoutMs;

    /** sessionId -> 进行中的生成。会话粒度互斥：生成中再发言直接 409 */
    private final Map<Long, Generation> inFlight = new ConcurrentHashMap<>();

    public InterviewStreamService(InterviewAgent interviewAgent,
                                  ChatContextService chatContextService,
                                  InterviewSessionMapper sessionMapper,
                                  @Qualifier("agentGenExecutor") ThreadPoolTaskExecutor agentGenExecutor,
                                  @Qualifier("sseHeartbeatScheduler") ScheduledExecutorService heartbeatScheduler,
                                  MeterRegistry meterRegistry,
                                  @Value("${interview.stream.emitter-timeout-ms:300000}") long emitterTimeoutMs) {
        this.interviewAgent = interviewAgent;
        this.chatContextService = chatContextService;
        this.sessionMapper = sessionMapper;
        this.agentGenExecutor = agentGenExecutor;
        this.heartbeatScheduler = heartbeatScheduler;
        this.meterRegistry = meterRegistry;
        this.emitterTimeoutMs = emitterTimeoutMs;
    }

    @PostConstruct
    void startHeartbeat() {
        heartbeatScheduler.scheduleWithFixedDelay(this::heartbeat,
                HEARTBEAT_INTERVAL_SECONDS, HEARTBEAT_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    @PreDestroy
    void shutdownHeartbeat() {
        heartbeatScheduler.shutdownNow();
    }

    /**
     * 用户消息已由 controller 落库后调用：占用会话并异步启动生成。
     * 返回时生成任务已提交（首批 delta 尚未产生，订阅窗口总是来得及）。
     */
    public void startGeneration(long sessionId, long userId, MessageResponse userMsg) {
        Timer.Sample firstTokenSample = Timer.start(meterRegistry);
        Generation generation = new Generation(sessionId, userMsg.seq(), firstTokenSample);
        if (inFlight.putIfAbsent(sessionId, generation) != null) {
            throw new BusinessException(ErrorCode.CONFLICT, "面试官正在回复，请稍候");
        }
        try {
            agentGenExecutor.execute(() -> run(generation, userId));
        } catch (RejectedExecutionException e) {
            inFlight.remove(sessionId, generation);
            log.warn("生成队列已满，拒绝请求 [sessionId={}]", sessionId);
            throw new BusinessException(ErrorCode.CONFLICT, "当前咨询人数较多，请稍后再试");
        }
    }

    /** 订阅某会话的 SSE。afterSeq 为客户端 Last-Event-ID（= 用户消息 seq），用于重连回放。 */
    public SseEmitter subscribe(long sessionId, long userId, Long afterSeq) {
        // 归属校验：非本人会话按不存在处理（与全局惯例一致）
        InterviewSession owned = sessionMapper.selectById(sessionId);
        if (owned == null || !owned.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "会话不存在");
        }
        SseEmitter emitter = new SseEmitter(emitterTimeoutMs);
        Generation generation = inFlight.get(sessionId);
        if (generation != null) {
            // 重连且事件序号不落后于本轮生成：回放快照后并入直播
            generation.attach(emitter, afterSeq != null && afterSeq >= generation.userMsgSeq);
        } else {
            replayFromDb(sessionId, userId, afterSeq, emitter);
        }
        return emitter;
    }

    // ---------- 内部 ----------

    private void run(Generation generation, long userId) {
        try {
            MessageResponse reply = interviewAgent.replyStreaming(generation.sessionId, userId,
                    generation::publish);
            // writeAssistant/updatePhase 在 agent 内已完成，此处读最新状态随 done 下发
            InterviewSession session = sessionMapper.selectById(generation.sessionId);
            generation.complete(reply, session);
            log.info("[SSE] 生成完成 session={} userMsgSeq={} replySeq={} chars={}",
                    generation.sessionId, generation.userMsgSeq, reply.seq(),
                    reply.content() == null ? 0 : reply.content().length());
        } catch (Exception e) {
            meterRegistry.counter("interview.generation.errors").increment();
            log.error("[SSE] 生成失败 session={} userMsgSeq={}", generation.sessionId, generation.userMsgSeq, e);
            generation.fail("面试官开小差了，请重新发送");
        } finally {
            inFlight.remove(generation.sessionId, generation);
        }
    }

    /** 无进行中生成：按 afterSeq 从库回放已持久化的回复后立即收尾（"断线期间生成已完成"场景） */
    private void replayFromDb(long sessionId, long userId, Long afterSeq, SseEmitter emitter) {
        try {
            List<MessageResponse> newer = afterSeq == null ? List.of()
                    : chatContextService.list(sessionId, userId, 50, afterSeq);
            InterviewSession session = sessionMapper.selectById(sessionId);
            boolean any = false;
            for (MessageResponse m : newer) {
                if ("ASSISTANT".equals(m.role())) {
                    any = true;
                    send(emitter, "done", String.valueOf(m.seq()),
                            new StreamDone(m, session == null ? null : session.getAgentState(),
                                    session == null ? null : session.getStatus()));
                }
            }
            if (!any) {
                // 无可回放内容也必须发 done，否则浏览器 EventSource 会对静默关闭无限重连
                send(emitter, "done", String.valueOf(afterSeq == null ? 0 : afterSeq),
                        new StreamDone(null, session == null ? null : session.getAgentState(),
                                session == null ? null : session.getStatus()));
            }
            emitter.complete();
        } catch (Exception e) {
            log.warn("[SSE] 历史回放失败，直接收尾 [sessionId={}]", sessionId, e);
            emitter.complete();
        }
    }

    private void heartbeat() {
        for (Generation generation : inFlight.values()) {
            for (SseEmitter target : generation.liveSubscribers()) {
                generation.sendComment(target);
            }
        }
    }

    private void send(SseEmitter emitter, String event, String id, Object payload) {
        try {
            emitter.send(SseEmitter.event().name(event).id(id)
                    .data(payload, MediaType.APPLICATION_JSON));
        } catch (Exception e) {
            log.debug("[SSE] 发送失败（客户端可能已断开）: {}", e.getMessage());
        }
    }

    /** 单轮生成的广播单元 */
    private final class Generation {

        private final long sessionId;
        private final long userMsgSeq;
        private final Timer.Sample firstTokenSample;
        private final Object lock = new Object();
        private final StringBuilder accumulated = new StringBuilder();
        private final List<SseEmitter> subscribers = new ArrayList<>();
        private boolean finished;
        private boolean firstTokenSeen;
        /** 终态回放用：complete/fail 冻结的载荷（attach 撞上已完成生成的小窗口直接补发） */
        private StreamDone finalDone;
        private StreamError finalError;

        Generation(long sessionId, long userMsgSeq, Timer.Sample firstTokenSample) {
            this.sessionId = sessionId;
            this.userMsgSeq = userMsgSeq;
            this.firstTokenSample = firstTokenSample;
        }

        /** 生成线程逐 token 调用：追加缓冲并广播增量 */
        void publish(String chunk) {
            List<SseEmitter> targets;
            synchronized (lock) {
                if (finished) {
                    return;
                }
                accumulated.append(chunk);
                targets = List.copyOf(subscribers);
                if (!firstTokenSeen) {
                    // 首 token 延迟（W7 压测 P95 的数据源）：从生成任务提交到首个增量
                    firstTokenSeen = true;
                    firstTokenSample.stop(meterRegistry.timer("interview.first.token.latency"));
                }
            }
            for (SseEmitter target : targets) {
                send(target, "delta", String.valueOf(userMsgSeq), new StreamDelta(userMsgSeq, chunk));
            }
        }

        /** 订阅加入：withSnapshot 时先回放累计快照（替换语义），再并入后续直播 */
        void attach(SseEmitter emitter, boolean withSnapshot) {
            synchronized (lock) {
                if (finished) {
                    // 生成恰好在 inFlight 查到与 attach 之间完成：按冻结终态补发后收尾
                    if (finalError != null) {
                        send(emitter, "error", String.valueOf(userMsgSeq), finalError);
                    } else {
                        if (withSnapshot && accumulated.length() > 0) {
                            send(emitter, "full-delta", String.valueOf(userMsgSeq),
                                    new StreamDelta(userMsgSeq, accumulated.toString()));
                        }
                        send(emitter, "done", String.valueOf(
                                        finalDone != null && finalDone.message() != null
                                                ? finalDone.message().seq() : userMsgSeq),
                                finalDone);
                    }
                    completeQuietly(emitter);
                    return;
                }
                subscribers.add(emitter);
                registerLifecycle(emitter);
                if (withSnapshot && accumulated.length() > 0) {
                    send(emitter, "full-delta", String.valueOf(userMsgSeq),
                            new StreamDelta(userMsgSeq, accumulated.toString()));
                }
            }
        }

        void complete(MessageResponse reply, InterviewSession session) {
            List<SseEmitter> targets;
            synchronized (lock) {
                finished = true;
                finalDone = new StreamDone(reply,
                        session == null ? null : session.getAgentState(),
                        session == null ? null : session.getStatus());
                targets = List.copyOf(subscribers);
                subscribers.clear();
            }
            for (SseEmitter target : targets) {
                send(target, "done", String.valueOf(reply.seq()), finalDone);
                completeQuietly(target);
            }
        }

        void fail(String userMessage) {
            List<SseEmitter> targets;
            synchronized (lock) {
                finished = true;
                finalError = new StreamError(userMessage);
                targets = List.copyOf(subscribers);
                subscribers.clear();
            }
            for (SseEmitter target : targets) {
                send(target, "error", String.valueOf(userMsgSeq), finalError);
                completeQuietly(target);
            }
        }

        /** 心跳用：锁内取存活订阅者快照 */
        List<SseEmitter> liveSubscribers() {
            synchronized (lock) {
                return finished ? List.of() : List.copyOf(subscribers);
            }
        }

        void sendComment(SseEmitter emitter) {
            synchronized (lock) {
                if (finished) {
                    return;
                }
                try {
                    emitter.send(SseEmitter.event().comment("hb"));
                } catch (Exception e) {
                    subscribers.remove(emitter);
                }
            }
        }

        private void registerLifecycle(SseEmitter emitter) {
            Runnable remove = () -> {
                synchronized (lock) {
                    subscribers.remove(emitter);
                }
            };
            emitter.onCompletion(remove);
            emitter.onTimeout(remove);
            emitter.onError(t -> remove.run());
        }

        private void completeQuietly(SseEmitter emitter) {
            try {
                emitter.complete();
            } catch (Exception e) {
                log.debug("[SSE] emitter 关闭异常（客户端可能已断开）: {}", e.getMessage());
            }
        }
    }
}
