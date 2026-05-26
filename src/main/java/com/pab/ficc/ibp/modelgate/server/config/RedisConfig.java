package com.pab.ficc.ibp.modelgate.server.config;

import com.pab.framework.redis.CacheProvider;
import com.pab.framework.redis.RedisClusterProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RedisConfig {

    @Value("${redis-cluster-key}")
    private String redisClusterKey;

    @Bean
    public CacheProvider cacheProvider() throws Exception {
        return RedisClusterProvider.getInstance(redisClusterKey);
    }
}
