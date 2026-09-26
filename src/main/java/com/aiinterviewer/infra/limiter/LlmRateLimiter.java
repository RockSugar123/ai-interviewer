package com.aiinterviewer.infra.limiter;

import com.aiinterviewer.common.BusinessException;
import com.aiinterviewer.common.ErrorCode;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;

/**
 * LLM 限流（FR-16，阶段 6）：
 * - 用户级发言 QPS：Redis + Lua 令牌桶，原子扣减（挂载在发言接口，LLM 调用的唯一用户入口）；
 * - 全局 token 日配额：Redis 按日计数器（INCRBY），发言前检查 + usage 回来后记账；
 *   记账点覆盖生成（writeAssistant）与报告（ReportService）；决策/评分调用 token 占比小且不落库，暂不记账（有意取舍）。
 * 故障语义：限流器自身 Redis 异常一律降级放行（限流是保护措施，不能反杀主链路，与 FR-2 降级原则一致）。
 */
@Slf4j
@Component
public class LlmRateLimiter {

    /** 令牌桶：KEYS[1]=桶键；ARGV=[填充速率/秒, 容量, 当前毫秒]。返回 1=放行 0=拒绝。 */
    private static final String TOKEN_BUCKET_LUA = """
            local rate = tonumber(ARGV[1])
            local cap = tonumber(ARGV[2])
            local now = tonumber(ARGV[3])
            local tokens = tonumber(redis.call('get', KEYS[1] .. ':t') or cap)
            local ts = tonumber(redis.call('get', KEYS[1] .. ':ts') or now)
            tokens = math.min(cap, tokens + (now - ts) * rate / 1000)
            local allowed = 0
            if tokens >= 1 then
                tokens = tokens - 1
                allowed = 1
            end
            local ttl = math.ceil(cap / rate) * 2 + 60
            redis.call('set', KEYS[1] .. ':t', tokens, 'EX', ttl)
            redis.call('set', KEYS[1] .. ':ts', now, 'EX', ttl)
            return allowed
            """;

    private final StringRedisTemplate redis;
    private final RateLimitProperties props;
    private final DefaultRedisScript<Long> bucketScript = new DefaultRedisScript<>(TOKEN_BUCKET_LUA, Long.class);
    private final Counter rejectedSpeak;
    private final Counter rejectedQuota;

    public LlmRateLimiter(StringRedisTemplate redis, RateLimitProperties props, MeterRegistry registry) {
        this.redis = redis;
        this.props = props;
        this.rejectedSpeak = registry.counter("interview.ratelimit.rejected", "type", "speak");
        this.rejectedQuota = registry.counter("interview.ratelimit.rejected", "type", "quota");
    }

    /** 发言前检查：令牌桶 + 全局日配额，任一不过抛 429 */
    public void checkSpeakAllowed(long userId) {
        if (!props.enabled()) {
            return;
        }
        try {
            Long allowed = redis.execute(bucketScript, List.of("rl:speak:" + userId),
                    String.valueOf(props.speakRefillPerSecond()),
                    String.valueOf(props.speakCapacity()),
                    String.valueOf(System.currentTimeMillis()));
            if (allowed == null || allowed != 1) {
                rejectedSpeak.increment();
                throw new BusinessException(ErrorCode.TOO_MANY_REQUESTS);
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("[Limiter] 令牌桶 Redis 异常，降级放行 [userId={}]", userId, e);
        }
        checkQuota();
    }

    private void checkQuota() {
        try {
            String used = redis.opsForValue().get(quotaKey());
            if (used != null && Long.parseLong(used) >= props.dailyTokenQuota()) {
                rejectedQuota.increment();
                log.warn("[Limiter] 全局 token 日配额已用尽 [used={}]", used);
                throw new BusinessException(ErrorCode.QUOTA_EXCEEDED);
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("[Limiter] 配额检查 Redis 异常，降级放行", e);
        }
    }

    /** usage 回来后记账（异步链路调用，失败不影响主流程） */
    public void recordTokens(int tokens) {
        if (tokens <= 0) {
            return;
        }
        try {
            String key = quotaKey();
            redis.opsForValue().increment(key, tokens);
            redis.expire(key, Duration.ofDays(2));
        } catch (Exception e) {
            log.warn("[Limiter] token 记账 Redis 异常（不阻塞） [tokens={}]", tokens, e);
        }
    }

    private String quotaKey() {
        return "quota:token:" + LocalDate.now();
    }
}
