package com.aiinterviewer.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/**
 * 异步基础设施（W3 SSE 流式）：
 * - agentGenExecutor：LLM 生成任务专用线程池，与 Tomcat 请求线程分离（SseEmitter 异步 servlet 下请求线程立即释放）
 * - sseHeartbeatScheduler：SSE 心跳，周期发 comment 事件防代理/浏览器空闲断连
 * 阶段 5 起文档索引任务迁 MQ（ResumeIndexListener 单线程消费），原 ragIndexExecutor 已删除。
 */
@Configuration
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

    @Bean(destroyMethod = "shutdown")
    public ScheduledExecutorService sseHeartbeatScheduler() {
        return Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "sse-heartbeat");
            t.setDaemon(true);
            return t;
        });
    }
}
