package com.pab.ficc.ibp.modelgate.server.config;

import com.pab.framework.redis.CacheProvider;
import redis.clients.jedis.Jedis;

import java.util.List;

/**
 * 验证 {@code RedisConfig.JedisCacheProvider.eval} 的 EVALSHA + NOSCRIPT 回退。
 *
 * <p>与项目其它测试一致，用 {@code main()} 实现，不引入测试框架。
 *
 * <p><b>需要本地 Redis 可达</b>（读 application.yml 里的 redis.local.*）。
 * 连不上时直接跳过并打印说明，不算失败——本测试的目的是验证逻辑，
 * 不是把构建绑死在有没有 Redis 上。
 *
 * <p>运行方式（项目根目录）：
 * <pre>
 * java -cp "target/classes;target/test-classes;$(cat cp.txt)" \
 *      com.pab.ficc.ibp.modelgate.server.config.EvalShaTest
 * </pre>
 */
public class EvalShaTest {

    private static final String HOST = "127.0.0.1";
    private static final int PORT = 6379;
    private static final String PASSWORD = "admin123456";

    private static int pass = 0;
    private static int fail = 0;

    public static void main(String[] args) {
        if (!redisReachable()) {
            System.out.println("跳过：本地 Redis(" + HOST + ":" + PORT + ") 不可达，无法验证 EVALSHA");
            return;
        }

        // 用带随机后缀的脚本，保证它一定不在 Redis 的脚本缓存里，从而真正走一次 NOSCRIPT 回退
        String marker = "mg_evalsha_test_" + System.nanoTime();
        String script = "return '" + marker + "'";
        String sha = sha1Hex(script);

        // 前置：确认 Redis 侧确实没有这段脚本
        try (Jedis j = jedis()) {
            check("前置：脚本尚未缓存在 Redis", Boolean.FALSE.equals(j.scriptExists(sha)));
        }

        CacheProvider provider = new RedisConfig.JedisCacheProvider(
                HOST, PORT, PASSWORD, 8, 4, 1, 3000, 60000, 300000);

        // 第一次调用：EVALSHA 会 NOSCRIPT，内部回退到 EVAL
        Object r1 = provider.eval(script, List.of(), List.of());
        check("首次调用能拿到正确结果（走 NOSCRIPT 回退）", marker.equals(String.valueOf(r1)));

        // 回退用的是 EVAL，Redis 会顺带缓存住脚本
        try (Jedis j = jedis()) {
            check("回退后脚本已被 Redis 缓存", Boolean.TRUE.equals(j.scriptExists(sha)));
        }

        // 第二次调用：此时应命中 EVALSHA，不再传脚本正文
        Object r2 = provider.eval(script, List.of(), List.of());
        check("二次调用结果一致（走 EVALSHA）", marker.equals(String.valueOf(r2)));

        // 模拟 Redis 重启 / SCRIPT FLUSH：脚本缓存被清空后仍要能自动恢复
        try (Jedis j = jedis()) {
            j.scriptFlush();
        }
        Object r3 = provider.eval(script, List.of(), List.of());
        check("SCRIPT FLUSH 后自动回退并恢复", marker.equals(String.valueOf(r3)));

        // 带 KEYS/ARGV 的真实形态也要能跑通
        Object r4 = provider.eval("return {KEYS[1], ARGV[1]}",
                List.of("mg:test:key"), List.of("42"));
        check("KEYS/ARGV 传递正常", String.valueOf(r4).contains("42"));

        System.out.println();
        System.out.println("通过 " + pass + " / " + (pass + fail));
        if (fail > 0) {
            System.exit(1);
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

    /** 与 RedisConfig 内部实现保持一致的 SHA1 */
    private static String sha1Hex(String script) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-1")
                    .digest(script.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            char[] hex = "0123456789abcdef".toCharArray();
            char[] out = new char[digest.length * 2];
            for (int i = 0; i < digest.length; i++) {
                int v = digest[i] & 0xFF;
                out[i * 2] = hex[v >>> 4];
                out[i * 2 + 1] = hex[v & 0x0F];
            }
            return new String(out);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
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
