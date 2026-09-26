package com.aiinterviewer.infra.limiter;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 限流与配额配置（FR-16，阶段 6）。
 * enabled=false 全部放行（限流器自身故障也降级放行——限流是保护措施，不能反杀主链路）。
 */
@ConfigurationProperties("interview.ratelimit")
public record RateLimitProperties(
        @DefaultValue("true") boolean enabled,
        /** 用户级发言令牌桶容量（突发额度） */
        @DefaultValue("5") int speakCapacity,
        /** 令牌填充速率（条/秒），0.5 = 平均 2 秒一条 */
        @DefaultValue("0.5") double speakRefillPerSecond,
        /** 全局每日 token 配额（所有用户共享，LLM 成本硬上限） */
        @DefaultValue("2000000") long dailyTokenQuota) {
}
