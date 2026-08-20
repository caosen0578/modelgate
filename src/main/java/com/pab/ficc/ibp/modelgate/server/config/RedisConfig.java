package com.pab.ficc.ibp.modelgate.server.config;

import com.pab.framework.redis.CacheProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.params.SetParams;

import java.util.List;

/**
 * Redis {@link CacheProvider} 装配：jedis 直连，读 {@code redis.local.*}（application.yml）。
 *
 * <p>不再按 Profile 区分（决策：数据源与 Redis 统一直连，不再依赖内网 Redis 集群 SDK）。
 * 所有环境走同一个 Bean，差异只在 {@code redis.local.*} 配置值——
 * 部署到不同环境时直接改 application.yml 里对应的值即可，不依赖 Spring Profile 机制。
 * 仅实现本项目实际调用的 get/setex/del/eval 四个方法。
 *
 * <p>池参数（{@code redis.local.pool.*}）externalize 到 application.yml（2026-08-18 压测优化后，
 * 便于生产环境直接改配置调整、不用重新编译）。{@code @Value} 里的默认值与 application.yml 当前写的
 * 生产评估值一致——yml 里删掉某一项不会失效，只是退回这里写的同一个默认值，两处应保持同步。
 */
@Configuration
public class RedisConfig {

    @Bean
    public CacheProvider cacheProvider(
            @Value("${redis.local.host:127.0.0.1}") String host,
            @Value("${redis.local.port:6379}") int port,
            @Value("${redis.local.password:}") String password,
            @Value("${redis.local.pool.max-total:100}") int maxTotal,
            @Value("${redis.local.pool.max-idle:32}") int maxIdle,
            @Value("${redis.local.pool.min-idle:10}") int minIdle,
            @Value("${redis.local.pool.max-wait-millis:3000}") int maxWaitMillis,
            @Value("${redis.local.pool.time-between-eviction-runs-millis:60000}") long timeBetweenEvictionRunsMillis,
            @Value("${redis.local.pool.min-evictable-idle-time-millis:300000}") long minEvictableIdleTimeMillis) {
        return new JedisCacheProvider(host, port, password, maxTotal, maxIdle, minIdle,
                maxWaitMillis, timeBetweenEvictionRunsMillis, minEvictableIdleTimeMillis);
    }

    static class JedisCacheProvider implements CacheProvider {

        private final JedisPool pool;

        JedisCacheProvider(String host, int port, String password, int maxTotal, int maxIdle, int minIdle,
                int maxWaitMillis, long timeBetweenEvictionRunsMillis, long minEvictableIdleTimeMillis) {
            JedisPoolConfig config = new JedisPoolConfig();
            config.setMaxTotal(maxTotal);
            config.setMaxIdle(maxIdle);
            config.setMinIdle(minIdle);
            // maxWaitMillis 此前从未设置，继承 commons-pool2 默认值 -1（无限等待）——池耗尽时借连接的
            // 线程会永久挂起而不是抛异常，QuotaFilter 的 fail-open 逻辑（catch Exception 才放行）
            // 根本触发不到，故显式配置：耗尽了就快速失败、走 fail-open。
            config.setMaxWaitMillis(maxWaitMillis);
            // 不用 testOnBorrow——那是每次借连接都多一次 PING 往返，等于把校验开销摊到请求热路径上。
            // 用 testWhileIdle + 后台淘汰线程，只校验闲置连接，不影响请求延迟（与 Druid 配置同一思路）。
            // 这两项不 externalize——固定用最佳实践值，没有按环境调整的必要。
            config.setTestOnBorrow(false);
            config.setTestOnReturn(false);
            config.setTestWhileIdle(true);
            config.setTimeBetweenEvictionRunsMillis(timeBetweenEvictionRunsMillis);
            config.setMinEvictableIdleTimeMillis(minEvictableIdleTimeMillis);
            if (password != null && !password.isEmpty()) {
                this.pool = new JedisPool(config, host, port, 2000, password);
            } else {
                this.pool = new JedisPool(config, host, port, 2000);
            }
        }

        @Override
        public String get(String key) {
            try (Jedis jedis = pool.getResource()) {
                return jedis.get(key);
            }
        }

        @Override
        public String setex(String key, int seconds, String value) {
            try (Jedis jedis = pool.getResource()) {
                return jedis.setex(key, seconds, value);
            }
        }

        @Override
        public Long del(String key) {
            try (Jedis jedis = pool.getResource()) {
                return jedis.del(key);
            }
        }

        /**
         * 脚本正文 → SHA1 的本地缓存。
         *
         * <p>脚本都是类里的静态常量，数量是个位数，用 ConcurrentHashMap 存足够，
         * 不需要淘汰策略。key 是脚本正文本身，天然去重。
         */
        private final java.util.concurrent.ConcurrentHashMap<String, String> scriptShaCache =
                new java.util.concurrent.ConcurrentHashMap<>();

        /**
         * Lua 脚本执行，走 <b>EVALSHA + NOSCRIPT 回退</b>。
         *
         * <p>原实现直接 {@code jedis.eval(script, ...)}，也就是<b>每个请求都把整段脚本正文
         * 完整传一遍</b>。配额检查脚本约 600 字节，而它在 {@code /v1/**} 热路径上每请求执行一次——
         * 纯粹是白烧的带宽与 Redis 侧的解析开销。EVALSHA 只传 40 字节的 SHA1。
         *
         * <p>标准做法是先 {@code SCRIPT LOAD} 再 EVALSHA，这里用更省事也更健壮的等价写法：
         * <b>本地算出 SHA1 直接 EVALSHA</b>，只有 Redis 说不认识（NOSCRIPT）时才回退到 EVAL
         * 送一次正文——而 EVAL 本身会让 Redis 缓存住这段脚本，于是下一次 EVALSHA 就命中了。
         * 这样"预热"是自动完成的，且天然覆盖 <b>Redis 重启 / SCRIPT FLUSH / 主从切换</b>
         * 这些会让脚本缓存失效的场景：失效后第一个请求回退一次，之后继续走 EVALSHA。
         *
         * <p>SHA1 是 Redis 对脚本正文的标准寻址方式，本地算与服务端算结果一致，不存在歧义。
         */
        @Override
        public Object eval(String script, List<String> keys, List<String> args) {
            String sha = scriptShaCache.computeIfAbsent(script, JedisCacheProvider::sha1Hex);
            try (Jedis jedis = pool.getResource()) {
                try {
                    return jedis.evalsha(sha, keys, args);
                } catch (redis.clients.jedis.exceptions.JedisNoScriptException e) {
                    // Redis 侧没有这段脚本：首次调用、或 Redis 重启 / SCRIPT FLUSH 之后。
                    // 用 EVAL 送一次正文，Redis 会顺带把它缓存起来，后续请求自动回到 EVALSHA。
                    return jedis.eval(script, keys, args);
                }
            }
        }

        /** 计算脚本的 SHA1（十六进制小写），与 Redis 内部的寻址方式一致 */
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
            } catch (java.security.NoSuchAlgorithmException e) {
                throw new IllegalStateException("SHA-1 不可用，无法使用 EVALSHA", e);
            }
        }

        @Override
        public String set(String key, String value, SetParams setParams) {
            try (Jedis jedis = pool.getResource()) {
                return jedis.set(key, value, setParams);
            }
        }
    }
}
