package com.pab.ficc.ibp.modelgate.server.config;

import com.pab.framework.redis.CacheProvider;
import com.pab.framework.redis.RedisClusterProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

import java.util.List;

/**
 * Redis CacheProvider 装配。
 *
 * <ul>
 *   <li>非 local：内网 Redis 集群（{@link RedisClusterProvider}，配置经 Apollo {@code redis-cluster-key} 下发）。</li>
 *   <li>local：jedis 直连本地 Redis（{@code redis.local.*}，见 application-local.yml），
 *       仅实现本项目用到的 get/setex/del/eval。<b>本地兼容件，合入内网时删除。</b></li>
 * </ul>
 */
@Configuration
public class RedisConfig {

    /** 非 local：内网 Redis 集群。 */
    @Bean
    @Profile("!local")
    public CacheProvider cacheProvider(@Value("${redis-cluster-key}") String redisClusterKey) throws Exception {
        return RedisClusterProvider.getInstance(redisClusterKey);
    }

    /** local：jedis 直连本地 Redis。 */
    @Bean
    @Profile("local")
    public CacheProvider localCacheProvider(
            @Value("${redis.local.host:127.0.0.1}") String host,
            @Value("${redis.local.port:6379}") int port,
            @Value("${redis.local.password:}") String password) {
        return new LocalJedisCacheProvider(host, port, password);
    }

    /** 本地 jedis CacheProvider：仅实现代码实际调用的 4 个方法。 */
    static class LocalJedisCacheProvider implements CacheProvider {

        private final JedisPool pool;

        LocalJedisCacheProvider(String host, int port, String password) {
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
    }
}
