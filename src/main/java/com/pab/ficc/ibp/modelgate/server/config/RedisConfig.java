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
 * 所有环境走同一个 Bean，差异只在 {@code redis.local.*} 这三个配置值——
 * 部署到不同环境时直接改 application.yml 里的这三行即可，不依赖 Spring Profile 机制。
 * 仅实现本项目实际调用的 get/setex/del/eval 四个方法。
 */
@Configuration
public class RedisConfig {

    @Bean
    public CacheProvider cacheProvider(
            @Value("${redis.local.host:127.0.0.1}") String host,
            @Value("${redis.local.port:6379}") int port,
            @Value("${redis.local.password:}") String password) {
        return new JedisCacheProvider(host, port, password);
    }

    static class JedisCacheProvider implements CacheProvider {

        private final JedisPool pool;

        JedisCacheProvider(String host, int port, String password) {
            // 池大小：生产评估值，maxTotal=100（2026-08-18 压测优化，原硬编码 16 已证实偏小）。
            // maxWaitMillis 此前从未设置，继承 commons-pool2 默认值 -1（无限等待）——
            // 池耗尽时借连接的线程会永久挂起而不是抛异常，QuotaFilter 的 fail-open 逻辑
            // （catch Exception 才放行）根本触发不到。显式设 3s，耗尽了就快速失败、走 fail-open。
            JedisPoolConfig config = new JedisPoolConfig();
            config.setMaxTotal(100);
            config.setMaxIdle(32);
            config.setMinIdle(10);
            config.setMaxWaitMillis(3000);
            // 不用 testOnBorrow——那是每次借连接都多一次 PING 往返，等于把校验开销摊到请求热路径上。
            // 用 testWhileIdle + 后台淘汰线程，只校验闲置连接，不影响请求延迟（与 Druid 配置同一思路）。
            config.setTestOnBorrow(false);
            config.setTestOnReturn(false);
            config.setTestWhileIdle(true);
            config.setTimeBetweenEvictionRunsMillis(60_000);
            config.setMinEvictableIdleTimeMillis(300_000);
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
