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

/**
 * 基于 Redis 原生计数命令的限流服务，支持 QPS、日调用量、月 Token 三层配额。
 *
 * <p><b>⚠️ 禁止使用 Lua 脚本（2026-08-24 内网实测结论）。</b>
 * 内网 Redis 禁用了 {@code EVAL} / {@code EVALSHA} / {@code SCRIPT LOAD}，
 * 本类原先的四段 Lua 脚本（{@code RATE_LIMIT_SCRIPT} / {@code MONTHLY_GET_SCRIPT} /
 * {@code MONTHLY_INCRBY_SCRIPT} / {@code COMBINED_QUOTA_SCRIPT}）已整体删除，
 * 改用 {@code INCR} / {@code INCRBY} / {@code GET} / {@code EXPIRE} 原生命令。
 * <b>后续任何"用一小段脚本保证原子性"的改法在内网都会直接报错，不要再写回来。</b>
 *
 * <h3>去掉 Lua 之后，原子性还成立吗？</h3>
 * <p>成立。原来用 Lua 只为两件事，逐一看：
 * <ol>
 *   <li><b>计数本身的原子性</b>——由 {@code INCR} / {@code INCRBY} 单命令保证，
 *       Redis 单线程执行，多实例并发不会丢计数。这一条和 Lua 无关，从来就不需要脚本。</li>
 *   <li><b>"首次写入才设 TTL"这个条件判断</b>——挪到客户端做：{@code INCR} 返回 1 时再发
 *       {@code EXPIRE}。两步之间不再原子，最坏情况是<b>进程在 INCR 之后、EXPIRE 之前挂掉，
 *       留下一个没有 TTL 的 key</b>。
 *       <p><b>这个残留不会造成配额判定错误</b>，因为本类三种 key 的名字里都带时间桶后缀：
 *       QPS key 带秒级时间戳、日 key 带 {@code yyyy-MM-dd}、月 key 带 {@code yyyy-MM}。
 *       下一个时间窗口用的是<b>另一个 key</b>，孤儿 key 再也不会被读到，只是白占一点内存
 *       （量级：一次非正常退出最多泄漏 3 个 key）。
 *       这正是"能安全地把条件判断挪到客户端"的前提——<b>如果将来把时间桶从 key 名里拿掉、
 *       改成靠 TTL 滚动窗口，这个结论立刻失效，必须重新设计</b>。</li>
 * </ol>
 *
 * <h3>热路径代价</h3>
 * <p>{@link #checkAllQuotas} 原先是一次 {@code eval} 往返，现在按层短路发命令：
 * 稳态下（计数器已存在、TTL 已设好）<b>QPS 1 次 + 日 1 次 + 月 1 次 = 最多 3 次</b>；
 * 每个时间窗口的第一个请求各多一次 {@code EXPIRE}。
 * <b>短路语义与 Lua 版完全一致</b>：QPS 超限就不再触碰日计数器，日超限不再触碰月计数器——
 * 顺序不能改，改了会让被限流的请求也白白消耗日配额。
 * <p>代价的实质不在端到端延迟（LLM 请求本身几十秒，几次内网 RTT 可忽略），而在
 * <b>Jedis 同步阻塞客户端的连接借还次数</b>：由 1 次升到最多 3 次。
 * 这是内网禁 Lua 的硬约束换来的，无法再压缩——除非引入 pipeline，
 * 但 pipeline 需要在 {@code CacheProvider} 上加非标准方法，会破坏"删桩即可合入内网"的前提，
 * 故不做（见 {@code CacheProvider} 类注释）。
 *
 * <p><b>降级策略：</b>Redis 不可用时所有方法均 fail-open（放行），
 * 避免 Redis 故障导致业务完全中断。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RateLimitService {

    private final CacheProvider cacheProvider;

    /**
     * QPS 计数 key 的 TTL（秒）。
     *
     * <p>key 里带秒级时间戳、每秒自然滚动，TTL 只负责清理旧 key，取 2s 是
     * "当前秒 + 1 秒缓冲"，避免机器间时钟微小偏差导致 key 刚建就过期。
     */
    private static final int QPS_KEY_TTL_SECONDS = 2;

    @Value("${modelgate.rate-limit.default-rate:10}")
    private int defaultRate;

    /** {@link #checkAllQuotas} 的返回码 */
    public static final int QUOTA_OK = 0;
    public static final int QUOTA_QPS_EXCEEDED = 1;
    public static final int QUOTA_DAILY_EXCEEDED = 2;
    public static final int QUOTA_MONTHLY_EXCEEDED = 3;

    /**
     * 三层配额检查（QPS → 日调用量 → 月 Token），按层短路。
     *
     * <p>三个上限都 ≤0（全不限）时<b>直接返回，一条 Redis 命令都不发</b>——
     * 否则每个不设配额的请求都要白白付一次往返。
     *
     * <p>月 Token 这一层只做 {@code GET} 只读预检，实际消耗由
     * {@link #addMonthlyTokens} 在响应完成后由 AuditService 批量写入，
     * 预检与实际消耗之间存在约 2s 的最终一致窗口，属既有设计权衡，本次改造未改变。
     *
     * @return 0=全部通过，1=QPS 超限，2=日调用量超限，3=月 Token 超限
     */
    public int checkAllQuotas(String appId, String modelId,
                              int qpsLimit, int dailyLimit, long monthlyLimit) {
        if (qpsLimit <= 0 && dailyLimit <= 0 && monthlyLimit <= 0) {
            return QUOTA_OK;
        }
        try {
            if (qpsLimit > 0) {
                String key = "rl:quota:qps:" + appId + ":" + modelId + ":"
                        + (System.currentTimeMillis() / 1000);
                if (incrWithTtl(key, QPS_KEY_TTL_SECONDS) > qpsLimit) {
                    return QUOTA_QPS_EXCEEDED;
                }
            }
            if (dailyLimit > 0) {
                LocalDate today = LocalDate.now();
                String key = "quota:daily:" + appId + ":" + modelId + ":" + today;
                if (incrWithTtl(key, secondsUntilTomorrow(today)) > dailyLimit) {
                    return QUOTA_DAILY_EXCEEDED;
                }
            }
            if (monthlyLimit > 0 && currentMonthlyTokens(appId, modelId) >= monthlyLimit) {
                return QUOTA_MONTHLY_EXCEEDED;
            }
            return QUOTA_OK;
        } catch (Exception e) {
            // fail-open：Redis 故障不能让业务全线中断
            log.warn("[RateLimitService] 配额检查失败，fail-open appId={} modelId={}: {}",
                    appId, modelId, e.getMessage());
            return QUOTA_OK;
        }
    }

    /**
     * 通用秒级滑动窗口限流（modelId 尚未解析出来时的全局兜底限流）。
     *
     * <p>key 包含秒级时间戳，每秒自然滚动到新 key，旧 key 在 TTL 后自动清理。
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
            return incrWithTtl("rl:" + apiKey + ":" + epochSecond, QPS_KEY_TTL_SECONDS) <= rateLimit;
        } catch (Exception e) {
            log.warn("[RateLimitService] Redis unavailable, fail-open apiKey={}: {}", apiKey, e.getMessage());
            return true; // fail-open：Redis 故障时放行，避免全局不可用
        }
    }

    /**
     * 累加实际消耗的 Token 数到月度计数器（由 AuditService 批量汇总后调用，<b>不在转发热路径</b>）。
     *
     * <p>key = {@code quota:monthly:{appId}:{modelId}:{yyyy-MM}}，TTL 至月末，自然月重置。
     */
    public void addMonthlyTokens(String appId, String modelId, long tokens) {
        if (tokens <= 0) return;
        try {
            String key = monthlyKey(appId, modelId);
            Long current = cacheProvider.incrBy(key, tokens);
            // 返回值等于本次增量 ⇒ key 是本次新建的 ⇒ 补设 TTL。
            // 与 incrWithTtl 同一套判断，只是起点从 1 换成 tokens。
            if (current != null && current == tokens) {
                cacheProvider.expire(key, secondsUntilNextMonth());
            }
        } catch (Exception e) {
            log.warn("[RateLimitService] Monthly token increment failed {}:{}: {}", appId, modelId, e.getMessage());
        }
    }

    public int getDefaultRate() {
        return defaultRate;
    }

    /**
     * {@code INCR key}，并在<b>首次写入时</b>补设 TTL，返回自增后的计数值。
     *
     * <p>取代原 Lua 的 {@code INCR + if current==1 then EXPIRE}。两步不再原子的后果
     * 已在类注释中论证：key 带时间桶后缀，最坏只留下孤儿 key，不影响配额判定。
     *
     * <p>不无条件 {@code EXPIRE} 是刻意的——那会让稳态下每次计数都多一次连接借还，
     * 而热路径上每请求要走三层配额，代价直接乘三。
     *
     * @param ttlSeconds 首次写入时设置的 TTL，调用方保证 ≥1
     */
    private long incrWithTtl(String key, int ttlSeconds) {
        Long current = cacheProvider.incr(key);
        long value = current == null ? 0L : current;
        if (value == 1L) {
            cacheProvider.expire(key, ttlSeconds);
        }
        return value;
    }

    /**
     * 读取月度 Token 当前累计值，key 不存在或值非法时返回 0。
     *
     * <p>原先用 Lua 的 {@code GET + tonumber}，现在直接 {@code GET} 后本地解析。
     * 值非数字只可能来自人为改库，此时返回 0（等于放行）而不是抛异常——
     * 抛出去会被上层 catch 成 fail-open，结果一样，但会刷 WARN 日志淹没真正的 Redis 故障。
     */
    private long currentMonthlyTokens(String appId, String modelId) {
        String raw = cacheProvider.get(monthlyKey(appId, modelId));
        if (raw == null || raw.isEmpty()) return 0L;
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            log.warn("[RateLimitService] 月度计数器值非法，按 0 处理 {}:{} value={}", appId, modelId, raw);
            return 0L;
        }
    }

    private String monthlyKey(String appId, String modelId) {
        return "quota:monthly:" + appId + ":" + modelId + ":" + YearMonth.now();
    }

    /** 距明日零点的秒数，保证日配额 key 在自然日结束时过期；至少 1 秒（EXPIRE 0 会立刻删 key）。 */
    private static int secondsUntilTomorrow(LocalDate today) {
        long ttl = ChronoUnit.SECONDS.between(LocalDateTime.now(),
                today.plusDays(1).atTime(LocalTime.MIDNIGHT));
        return (int) Math.max(ttl, 1L);
    }

    /** 距下月 1 日零点的秒数，保证月配额 key 在自然月结束时过期；最大约 31 天，不会溢出 int。 */
    private static int secondsUntilNextMonth() {
        long ttl = ChronoUnit.SECONDS.between(LocalDateTime.now(),
                YearMonth.now().plusMonths(1).atDay(1).atTime(LocalTime.MIDNIGHT));
        return (int) Math.max(ttl, 1L);
    }
}
