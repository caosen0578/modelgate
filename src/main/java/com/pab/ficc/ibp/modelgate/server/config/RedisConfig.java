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
            JedisPoolConfig config = new JedisPoolConfig();
            config.setMaxTotal(16);
            config.setMaxIdle(8);
            config.setMinIdle(1);
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
