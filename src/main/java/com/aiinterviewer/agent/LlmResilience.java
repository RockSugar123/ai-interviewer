package com.aiinterviewer.agent;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.github.resilience4j.micrometer.tagged.TaggedRetryMetrics;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * LLM 调用韧性（FR-17，阶段 6）：编程式 CircuitBreaker + Retry（不走注解 AOP——Agent 内部自调用注解会失效）。
 * 降级链 = 主模型（熔断保护 + 决策重试）→ 切备用模型（interview.agent.fallback-model）→ 兜底（决策安全降级 NEXT_QUESTION / 生成 error 事件）。
 * CB 打开后主模型调用快速失败（CallNotPermittedException），调用方立即走备用模型，不再陪主模型等超时。
 * 指标：resilience4j.circuitbreaker.* / resilience4j.retry.* 自动入 Micrometer（Grafana 可视化）；切换备用计数 interview.llm.fallback.invocations。
 */
@Component
public class LlmResilience {

    private final CircuitBreaker mainCb;
    private final Retry decisionRetry;
    private final Counter fallbackCounter;

    public LlmResilience(MeterRegistry registry) {
        CircuitBreakerConfig cbConfig = CircuitBreakerConfig.custom()
                .failureRateThreshold(50)                     // 失败率 50% 打开
                .slidingWindowSize(10)                        // 最近 10 次调用滑动统计
                .minimumNumberOfCalls(5)                      // 至少 5 次才开始评估
                .waitDurationInOpenState(Duration.ofSeconds(30)) // 打开 30s 后转半开试探
                .build();
        RetryConfig retryConfig = RetryConfig.custom()
                .maxAttempts(2)                               // 结构化输出偶发解析失败，重试 1 次足够
                .intervalFunction(IntervalFunction.ofExponentialBackoff(500, 2))
                .build();
        CircuitBreakerRegistry cbRegistry = CircuitBreakerRegistry.of(cbConfig);
        RetryRegistry retryRegistry = RetryRegistry.of(retryConfig);
        mainCb = cbRegistry.circuitBreaker("llmMain");
        decisionRetry = retryRegistry.retry("llmDecision");
        TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(cbRegistry).bindTo(registry);
        TaggedRetryMetrics.ofRetryRegistry(retryRegistry).bindTo(registry);
        fallbackCounter = registry.counter("interview.llm.fallback.invocations");
    }

    /** 主模型调用（生成/备用决策共用熔断保护） */
    public <T> T withMain(Supplier<T> call) {
        return mainCb.executeSupplier(call);
    }

    /** 决策调用：熔断 + 重试叠加 */
    public <T> T withDecision(Supplier<T> call) {
        return mainCb.executeSupplier(() -> decisionRetry.executeSupplier(call));
    }

    /** 统计切换备用模型次数（成功率/降级频率观测） */
    public void recordFallback() {
        fallbackCounter.increment();
    }
}
