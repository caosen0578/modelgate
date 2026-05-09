package com.pab.ficc.idp.modelgate.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Redis Lua 滑动窗口限流服务（令牌桶实现）
 * <p>
 * Lua 脚本保证原子性，避免分布式并发问题。
 * key 格式：rl:{apiKey}:{当前秒}
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RateLimitService {

    private final StringRedisTemplate redis;

    @Value("${modelgate.rate-limit.default-rate:10}")
    private int defaultRate;

    /**
     * Lua 令牌桶脚本：
     * KEYS[1] — 限流 key（rl:{apiKey}:{epoch_second}）
     * ARGV[1] — 令牌桶容量（burst）
     * ARGV[2] — TTL（秒）
     * 返回：1 = 允许，0 = 超限
     */
    private static final DefaultRedisScript<Long> RATE_LIMIT_SCRIPT = new DefaultRedisScript<>(
            """
            local key = KEYS[1]
            local limit = tonumber(ARGV[1])
            local ttl = tonumber(ARGV[2])
            local current = redis.call('INCR', key)
            if current == 1 then
                redis.call('EXPIRE', key, ttl)
            end
            if current > limit then
                return 0
            else
                return 1
            end
            """,
            Long.class
    );

    /**
     * 判断该 apiKey 当前秒内是否超出限流
     *
     * @param apiKey    调用方 key
     * @param rateLimit 每秒允许请求数（0 表示不限流）
     * @return true — 允许；false — 超限
     */
    public boolean isAllowed(String apiKey, int rateLimit) {
        if (rateLimit <= 0) {
            return true;
        }
        try {
            long epochSecond = System.currentTimeMillis() / 1000;
            String key = "rl:" + apiKey + ":" + epochSecond;
            Long result = redis.execute(RATE_LIMIT_SCRIPT, List.of(key),
                    String.valueOf(rateLimit), "2");
            return Long.valueOf(1L).equals(result);
        } catch (Exception e) {
            // Redis 故障时降级放行，避免限流器本身成为故障点
            log.warn("[RateLimitService] Redis unavailable, allow apiKey={}: {}", apiKey, e.getMessage());
            return true;
        }
    }
}
