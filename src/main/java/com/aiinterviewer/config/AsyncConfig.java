package com.aiinterviewer.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/**
 * 异步基础设施（W3 SSE 流式）：
 * - agentGenExecutor：LLM 生成任务专用线程池，与 Tomcat 请求线程分离（SseEmitter 异步 servlet 下请求线程立即释放）
 * - reviewExecutor：后台复盘 agent 专用线程池（阶段 7），逐题异步生成评分/参考答案素材，与主链路生成隔离
 * - sseHeartbeatScheduler：SSE 心跳，周期发 comment 事件防代理/浏览器空闲断连
 * 阶段 5 起文档索引任务迁 MQ（ResumeIndexListener 单线程消费），原 ragIndexExecutor 已删除。
 */
@Configuration
@Slf4j
public class AsyncConfig {

    @Bean("agentGenExecutor")
    public ThreadPoolTaskExecutor agentGenExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        // JDK 线程池"队列满才从 core 扩到 max"：LLM 生成为长任务，core<max 时 max 形同虚设，直接 core=max
        executor.setCorePoolSize(8);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("agent-gen-");
        executor.initialize();
        return executor;
    }

    /** 单线程：同一会话的复盘任务按提交顺序串行，且执行时重算复盘区间（WRAP_UP 与 finish 事件兜底
     * 相邻提交时，靠"上一条素材 seq_end"链式接力自然去重）；复盘为轻量后台任务，单线程足够 */
    @Bean("reviewExecutor")
    public ThreadPoolTaskExecutor reviewExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("review-agent-");
        // 队列满直接丢弃并记日志：复盘素材缺失只影响报告逐题章节，绝不反压/阻塞主链路
        executor.setRejectedExecutionHandler((r, e) ->
                log.warn("[Review] 复盘任务队列已满，丢弃本次复盘任务"));
        executor.initialize();
        return executor;
    }

    @Bean(destroyMethod = "shutdown")
    public ScheduledExecutorService sseHeartbeatScheduler() {
        return Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "sse-heartbeat");
            t.setDaemon(true);
            return t;
        });
    }
}
