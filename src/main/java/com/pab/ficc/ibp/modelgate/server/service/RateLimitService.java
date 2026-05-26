package com.pab.ficc.ibp.modelgate.server.service;

import com.pab.framework.redis.CacheProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class RateLimitService {

    private final CacheProvider cacheProvider;

    @Value("${modelgate.rate-limit.default-rate:10}")
    private int defaultRate;

    /**
     * Lua 滑动窗口限流脚本
     * KEYS[1]=计数key, ARGV[1]=最大请求数, ARGV[2]=key TTL（秒）
     * 返回：1=允许，0=超限
     */
    private static final String RATE_LIMIT_SCRIPT =
            "local current = redis.call('INCR', KEYS[1])\n" +
            "if current == 1 then\n" +
            "    redis.call('EXPIRE', KEYS[1], ARGV[2])\n" +
            "end\n" +
            "if current > tonumber(ARGV[1]) then\n" +
            "    return 0\n" +
            "else\n" +
            "    return 1\n" +
            "end";

    /** 读取月度 token 累计量（GET，无副作用） */
    private static final String MONTHLY_GET_SCRIPT =
            "local v = redis.call('GET', KEYS[1])\n" +
            "if v == false then return 0 end\n" +
            "return tonumber(v)";

    /** 原子 INCRBY + 首次写入时设置 TTL */
    private static final String MONTHLY_INCRBY_SCRIPT =
            "local current = redis.call('INCRBY', KEYS[1], ARGV[1])\n" +
            "if current == tonumber(ARGV[1]) then\n" +
            "    redis.call('EXPIRE', KEYS[1], ARGV[2])\n" +
            "end\n" +
            "return current";

    public boolean isAllowed(String apiKey, int rateLimit) {
        if (rateLimit <= 0) return true;
        try {
            long epochSecond = System.currentTimeMillis() / 1000;
            String key = "rl:" + apiKey + ":" + epochSecond;
            Object result = cacheProvider.eval(RATE_LIMIT_SCRIPT, List.of(key),
                    List.of(String.valueOf(rateLimit), "2"));
            return Long.valueOf(1L).equals(result);
        } catch (Exception e) {
            log.warn("[RateLimitService] Redis unavailable, fail-open apiKey={}: {}", apiKey, e.getMessage());
            return true;
        }
    }

    public boolean isQpsAllowed(String appId, String modelId, int qpsLimit) {
        if (qpsLimit <= 0) return true;
        return isAllowed("quota:qps:" + appId + ":" + modelId, qpsLimit);
    }

    public boolean isDailyAllowed(String appId, String modelId, int dailyLimit) {
        if (dailyLimit <= 0) return true;
        try {
            LocalDate today = LocalDate.now();
            String key = "quota:daily:" + appId + ":" + modelId + ":" + today;
            long ttl = ChronoUnit.SECONDS.between(LocalDateTime.now(),
                    today.plusDays(1).atTime(LocalTime.MIDNIGHT));
            Object result = cacheProvider.eval(RATE_LIMIT_SCRIPT, List.of(key),
                    List.of(String.valueOf(dailyLimit), String.valueOf(Math.max(ttl, 1))));
            return Long.valueOf(1L).equals(result);
        } catch (Exception e) {
            log.warn("[RateLimitService] Daily quota check failed, fail-open appId={}: {}", appId, e.getMessage());
            return true;
        }
    }

    /** 检查月度 token 配额是否未超限（小于 limit 即允许）。limit=0 表示不限。 */
    public boolean checkMonthlyTokens(String appId, String modelId, long monthlyLimit) {
        if (monthlyLimit <= 0) return true;
        try {
            Object result = cacheProvider.eval(MONTHLY_GET_SCRIPT,
                    List.of(monthlyKey(appId, modelId)), List.of());
            long current = result == null ? 0L : ((Number) result).longValue();
            return current < monthlyLimit;
        } catch (Exception e) {
            log.warn("[RateLimitService] Monthly token check failed, fail-open appId={}: {}", appId, e.getMessage());
            return true;
        }
    }

    /** 累加实际消耗的 token 数到月度计数器。tokens=0 时跳过。 */
    public void addMonthlyTokens(String appId, String modelId, long tokens) {
        if (tokens <= 0) return;
        try {
            YearMonth ym = YearMonth.now();
            long ttl = ChronoUnit.SECONDS.between(LocalDateTime.now(),
                    ym.plusMonths(1).atDay(1).atTime(LocalTime.MIDNIGHT));
            cacheProvider.eval(MONTHLY_INCRBY_SCRIPT,
                    List.of(monthlyKey(appId, modelId)),
                    List.of(String.valueOf(tokens), String.valueOf(Math.max(ttl, 1))));
        } catch (Exception e) {
            log.warn("[RateLimitService] Monthly token increment failed {}:{}: {}", appId, modelId, e.getMessage());
        }
    }

    public int getDefaultRate() {
        return defaultRate;
    }

    private String monthlyKey(String appId, String modelId) {
        return "quota:monthly:" + appId + ":" + modelId + ":" + YearMonth.now();
    }
}
