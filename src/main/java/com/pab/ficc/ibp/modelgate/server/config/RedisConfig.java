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

        @Override
        public Object eval(String script, List<String> keys, List<String> args) {
            try (Jedis jedis = pool.getResource()) {
                return jedis.eval(script, keys, args);
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
