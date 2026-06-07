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

/**
 * 基于 Redis Lua 脚本的限流服务，支持 QPS、日调用量、月 Token 三层配额。
 *
 * <p><b>实现原理：</b>所有计数操作通过 Lua 脚本在 Redis 服务端原子执行，
 * 避免多实例竞争导致的计数不准确（无需分布式锁）。
 *
 * <p><b>降级策略：</b>Redis 不可用时所有方法均 fail-open（返回 true 放行），
 * 避免 Redis 故障导致业务完全中断。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RateLimitService {

    private final CacheProvider cacheProvider;

    @Value("${modelgate.rate-limit.default-rate:10}")
    private int defaultRate;

    /**
     * QPS / 日调用量 滑动计数脚本（INCR + 条件 EXPIRE）。
     *
     * <p>逻辑：对 key 做 INCR，若是首次写入（值 == 1）则设置 TTL，
     * 再判断当前计数是否超过上限。
     * <ul>
     *   <li>QPS key 格式：{@code rl:{标识}:{秒级时间戳}}，TTL = 2s（覆盖当前秒 + 缓冲）</li>
     *   <li>日配额 key 格式：{@code quota:daily:{appId}:{modelId}:{日期}}，TTL = 至当天 24:00</li>
     * </ul>
     * KEYS[1]=计数key，ARGV[1]=最大请求数，ARGV[2]=key TTL（秒）；返回 1=允许，0=超限。
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

    /**
     * 只读取月度 Token 当前累计值，不修改计数器（用于配额预检查）。
     * KEYS[1]=月度计数 key；返回当前累计数（key 不存在返回 0）。
     */
    private static final String MONTHLY_GET_SCRIPT =
            "local v = redis.call('GET', KEYS[1])\n" +
            "if v == false then return 0 end\n" +
            "return tonumber(v)";

    /**
     * 原子 INCRBY + 首次写入时设置 TTL（月末过期）。
     *
     * <p>之所以在 Lua 中判断"是否首次写入"：INCRBY 不支持同时设置 TTL，
     * 若先 INCRBY 再 EXPIRE，多实例并发时可能重复 EXPIRE 导致计数器提前过期。
     * Lua 脚本保证两步操作原子性。
     * KEYS[1]=月度计数 key，ARGV[1]=增量，ARGV[2]=TTL（秒，至月末）。
     */
    private static final String MONTHLY_INCRBY_SCRIPT =
            "local current = redis.call('INCRBY', KEYS[1], ARGV[1])\n" +
            "if current == tonumber(ARGV[1]) then\n" +
            "    redis.call('EXPIRE', KEYS[1], ARGV[2])\n" +
            "end\n" +
            "return current";

    /**
     * 通用秒级滑动窗口限流。
     *
     * <p>key 包含秒级时间戳，每秒自然滚动到新 key，旧 key 在 TTL=2s 后自动清理。
     * 这种"计数器 + 时间戳后缀"是比令牌桶更轻量的滑动窗口实现，
     * 适合内网低延迟场景，误差在同一秒边界约为 ±1 个时间窗口。
     *
     * @param rateLimit 每秒最大请求数，≤0 表示不限
     * @return true=允许，false=超限
     */
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
            return true; // fail-open：Redis 故障时放行，避免全局不可用
        }
    }

    /** QPS 限流：key = {@code quota:qps:{appId}:{modelId}:{当前秒}}，TTL=2s。 */
    public boolean isQpsAllowed(String appId, String modelId, int qpsLimit) {
        if (qpsLimit <= 0) return true;
        return isAllowed("quota:qps:" + appId + ":" + modelId, qpsLimit);
    }

    /**
     * 日调用量限流：key = {@code quota:daily:{appId}:{modelId}:{yyyy-MM-dd}}，
     * TTL 精确到当天 24:00，自然日重置无需手动清理。
     */
    public boolean isDailyAllowed(String appId, String modelId, int dailyLimit) {
        if (dailyLimit <= 0) return true;
        try {
            LocalDate today = LocalDate.now();
            String key = "quota:daily:" + appId + ":" + modelId + ":" + today;
            // TTL = 从现在到明天零点的秒数，保证 key 在自然日结束时过期
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

    /**
     * 检查月度 Token 配额是否未超限（只读，不修改计数器）。
     *
     * <p>此方法只做预检查，实际消耗通过 {@link #addMonthlyTokens} 在响应完成后写入。
     * 预检与实际消耗之间存在轻微的最终一致窗口（约 2s 的批量写入延迟），
     * 属于设计权衡，不需要强一致。
     *
     * @param monthlyLimit 月度 Token 上限，≤0 表示不限
     */
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

    /**
     * 累加实际消耗的 Token 数到月度计数器（由 AuditService 批量汇总后调用）。
     *
     * <p>key = {@code quota:monthly:{appId}:{modelId}:{yyyy-MM}}，TTL 至月末，自然月重置。
     */
    public void addMonthlyTokens(String appId, String modelId, long tokens) {
        if (tokens <= 0) return;
        try {
            YearMonth ym = YearMonth.now();
            // TTL = 从现在到下月 1 日零点的秒数
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
