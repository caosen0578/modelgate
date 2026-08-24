package com.pab.ficc.ibp.modelgate.server.service;

import com.pab.ficc.ibp.modelgate.server.config.RedisConfig;
import com.pab.framework.redis.CacheProvider;
import redis.clients.jedis.Jedis;

/**
 * 验证<b>去 Lua 之后</b>的配额计数语义（{@link RateLimitService} + {@code JedisCacheProvider}
 * 的 INCR / INCRBY / EXPIRE）。
 *
 * <p>本测试取代已删除的 {@code EvalShaTest}——内网 Redis 禁用 Lua 脚本，
 * EVALSHA 那条链路整体移除，对应的回归也随之作废。
 *
 * <p>覆盖的正是"把 Lua 里的条件判断挪到客户端"之后<b>最容易回归</b>的几点：
 * <ol>
 *   <li>INCR 首次返回 1、TTL 被补上（不补 TTL 会让日/月计数器永不重置）</li>
 *   <li>非首次不重复 EXPIRE，即 TTL 不被刷新（刷新会让窗口无限后延，日配额形同虚设）</li>
 *   <li>超限判定用 {@code >} 而非 {@code >=}：limit=N 时第 N 个请求必须放行、第 N+1 个才拒</li>
 *   <li>三层短路：QPS 超限时日计数器<b>不能</b>被 INCR（否则被限流的请求白耗日配额）</li>
 *   <li>INCRBY 首次写入时补 TTL（月度 Token 累加走的是另一条分支）</li>
 * </ol>
 *
 * <p>与项目其它测试一致，用 {@code main()} 实现，不引入测试框架。
 *
 * <p><b>需要本地 Redis 可达</b>。连不上时直接跳过并打印说明，不算失败——
 * 本测试的目的是验证逻辑，不是把构建绑死在有没有 Redis 上。
 *
 * <p>运行方式（项目根目录）：
 * <pre>
 * java -cp "target/classes;target/test-classes;$(cat cp.txt)" \
 *      com.pab.ficc.ibp.modelgate.server.service.QuotaCounterTest
 * </pre>
 */
public class QuotaCounterTest {

    private static final String HOST = "127.0.0.1";
    private static final int PORT = 6379;
    private static final String PASSWORD = "admin123456";

    private static int pass = 0;
    private static int fail = 0;

    public static void main(String[] args) throws Exception {
        if (!redisReachable()) {
            System.out.println("跳过：本地 Redis(" + HOST + ":" + PORT + ") 不可达，无法验证配额计数");
            return;
        }

        CacheProvider provider = new RedisConfig.JedisCacheProvider(
                HOST, PORT, PASSWORD, 8, 4, 1, 3000, 60000, 300000);

        String suffix = ":" + System.nanoTime();

        // ① INCR 首次返回 1，随后补 TTL 生效
        String k1 = "mg:test:incr" + suffix;
        check("INCR 首次返回 1", Long.valueOf(1L).equals(provider.incr(k1)));
        provider.expire(k1, 60);
        long ttl1 = ttl(k1);
        check("首次写入后 TTL 已设置（当前 " + ttl1 + "s）", ttl1 > 0 && ttl1 <= 60);

        // ② 非首次不再 EXPIRE —— TTL 必须继续倒数而不是被刷回 60
        check("INCR 第二次返回 2", Long.valueOf(2L).equals(provider.incr(k1)));
        Thread.sleep(1100);
        long ttl2 = ttl(k1);
        check("未重复 EXPIRE，TTL 在倒数（" + ttl1 + "s → " + ttl2 + "s）", ttl2 < ttl1);

        // ③ 超限判定边界：limit=2 时第 2 个请求放行，第 3 个才拒
        RateLimitService svc = new RateLimitService(provider);
        String app = "t_app" + suffix.replace(":", "");
        String model = "t_model";
        // QPS key 带秒级时间戳：三次调用若跨过整秒边界会落到两个 key 上，判定失真。
        // 等到刚过整秒再开始，留出近 1 秒的余量，消除这个不稳定因素。
        awaitSecondBoundary();
        check("QPS limit=2 第 1 个放行", svc.checkAllQuotas(app, model, 2, 0, 0) == RateLimitService.QUOTA_OK);
        check("QPS limit=2 第 2 个放行", svc.checkAllQuotas(app, model, 2, 0, 0) == RateLimitService.QUOTA_OK);
        check("QPS limit=2 第 3 个拒绝",
                svc.checkAllQuotas(app, model, 2, 0, 0) == RateLimitService.QUOTA_QPS_EXCEEDED);

        // ④ 短路语义：上一步已经 QPS 超限，日计数器不能被碰过
        String dailyKey = "quota:daily:" + app + ":" + model + ":" + java.time.LocalDate.now();
        check("QPS 超限时未触碰日计数器", provider.get(dailyKey) == null);

        // ⑤ 全不限时一条命令都不发（用一个不存在的 key 反证）
        String app2 = app + "_nolimit";
        check("三项全不限直接返回 OK", svc.checkAllQuotas(app2, model, 0, 0, 0) == RateLimitService.QUOTA_OK);
        check("三项全不限未产生任何 key",
                provider.get("quota:daily:" + app2 + ":" + model + ":" + java.time.LocalDate.now()) == null);

        // ⑥ 月度 Token：INCRBY 首次写入补 TTL，累计值可被预检读到
        svc.addMonthlyTokens(app, model, 100);
        String monthKey = "quota:monthly:" + app + ":" + model + ":" + java.time.YearMonth.now();
        check("INCRBY 首次写入值正确", "100".equals(provider.get(monthKey)));
        check("INCRBY 首次写入已补 TTL（" + ttl(monthKey) + "s）", ttl(monthKey) > 0);
        svc.addMonthlyTokens(app, model, 50);
        check("INCRBY 累加正确", "150".equals(provider.get(monthKey)));
        check("无累计时月配额放行",
                svc.checkAllQuotas(app + "_m", model, 0, 0, 100) == RateLimitService.QUOTA_OK);
        check("月 Token 达上限即拒",
                svc.checkAllQuotas(app, model, 0, 0, 150) == RateLimitService.QUOTA_MONTHLY_EXCEEDED);

        // ⑦ 日配额独立生效（QPS 不限时不应短路掉日计数）
        String app3 = app + "_d";
        check("日 limit=1 第 1 个放行", svc.checkAllQuotas(app3, model, 0, 1, 0) == RateLimitService.QUOTA_OK);
        check("日 limit=1 第 2 个拒绝",
                svc.checkAllQuotas(app3, model, 0, 1, 0) == RateLimitService.QUOTA_DAILY_EXCEEDED);
        String dailyKey3 = "quota:daily:" + app3 + ":" + model + ":" + java.time.LocalDate.now();
        check("日计数器 TTL 指向当天结束（" + ttl(dailyKey3) + "s）",
                ttl(dailyKey3) > 0 && ttl(dailyKey3) <= 86400);

        cleanup(suffix, app, app2, app3, model, k1);

        System.out.println();
        System.out.println("通过 " + pass + " / " + (pass + fail));
        if (fail > 0) {
            System.exit(1);
        }
    }

    private static void cleanup(String suffix, String app, String app2, String app3,
                                String model, String k1) {
        try (Jedis j = jedis()) {
            j.del(k1);
            for (String a : new String[]{app, app + "_m", app2, app3}) {
                j.del("quota:daily:" + a + ":" + model + ":" + java.time.LocalDate.now());
                j.del("quota:monthly:" + a + ":" + model + ":" + java.time.YearMonth.now());
                // QPS key 带秒级时间戳，TTL 只有 2s，会自行消失，不逐个清理
            }
        } catch (Exception e) {
            System.out.println("清理测试 key 失败（不影响结论）：" + e.getMessage());
        }
    }

    /** 自旋到刚过整秒，保证紧接着的几次 QPS 调用落在同一个秒级 key 上 */
    private static void awaitSecondBoundary() throws InterruptedException {
        while (System.currentTimeMillis() % 1000 > 50) {
            Thread.sleep(10);
        }
    }

    private static long ttl(String key) {
        try (Jedis j = jedis()) {
            return j.ttl(key);
        }
    }

    private static boolean redisReachable() {
        try (Jedis j = jedis()) {
            return "PONG".equalsIgnoreCase(j.ping());
        } catch (Exception e) {
            return false;
        }
    }

    private static Jedis jedis() {
        Jedis j = new Jedis(HOST, PORT, 2000);
        if (PASSWORD != null && !PASSWORD.isEmpty()) {
            j.auth(PASSWORD);
        }
        return j;
    }

    private static void check(String name, boolean ok) {
        if (ok) {
            pass++;
            System.out.println("PASS " + name);
        } else {
            fail++;
            System.out.println("FAIL " + name);
        }
    }
}
