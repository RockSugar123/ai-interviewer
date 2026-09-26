package com.aiinterviewer.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/**
 * 异步基础设施（W3 SSE 流式）：
 * - agentGenExecutor：LLM 生成任务专用线程池，与 Tomcat 请求线程分离（SseEmitter 异步 servlet 下请求线程立即释放）
 * - sseHeartbeatScheduler：SSE 心跳，周期发 comment 事件防代理/浏览器空闲断连
 * 阶段 4 的文档索引任务（@Async）也挂在此开关下。
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean("agentGenExecutor")
    public ThreadPoolTaskExecutor agentGenExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
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
