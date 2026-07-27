package com.pab.ficc.ibp.modelgate.server.config;

import com.pab.framework.redis.CacheProvider;
import com.pab.framework.redis.RedisClusterProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Redis CacheProvider 装配。
 *
 * <ul>
 *   <li>非 local：内网 Redis 集群（{@link RedisClusterProvider}）。</li>
 *   <li>local：内存版 CacheProvider（动态代理实现完整接口，仅 get/setex/del/eval 有真实语义，
 *       其余方法返回类型默认值）——够本地 admin CRUD 联调，不需要真实 Redis。</li>
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

    /** local：内存版 CacheProvider（动态代理，无需真实 Redis）。 */
    @Bean
    @Profile("local")
    public CacheProvider localCacheProvider() {
        final ConcurrentHashMap<String, String> store = new ConcurrentHashMap<>();
        final ConcurrentHashMap<String, AtomicLong> counters = new ConcurrentHashMap<>();
        return (CacheProvider) Proxy.newProxyInstance(
                CacheProvider.class.getClassLoader(),
                new Class[]{CacheProvider.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "get":
                            if (args != null && args.length == 1 && args[0] instanceof String) {
                                return store.get((String) args[0]);
                            }
                            break;
                        case "setex":
                            if (args != null && args.length == 3 && args[0] instanceof String) {
                                store.put((String) args[0], String.valueOf(args[2]));
                                return "OK";
                            }
                            break;
                        case "del":
                            if (args != null && args.length == 1 && args[0] instanceof String) {
                                String k = (String) args[0];
                                counters.remove(k);
                                return store.remove(k) != null ? 1L : 0L;
                            }
                            break;
                        case "eval":
                            if (args != null && args.length == 3 && args[1] instanceof List) {
                                return evalStub((String) args[0], (List<String>) args[1], (List<String>) args[2], counters);
                            }
                            break;
                        default:
                            break;
                    }
                    return defaultValue(method.getReturnType());
                });
    }

    /** 模拟限流/月度计数脚本：INCRBY→累加返回；INCR→超限返0否则1；其余(GET)→返回当前累计。 */
    private static Object evalStub(String script, List<String> keys, List<String> args,
                                  ConcurrentHashMap<String, AtomicLong> counters) {
        String key = (keys == null || keys.isEmpty()) ? "" : keys.get(0);
        AtomicLong counter = counters.computeIfAbsent(key, k -> new AtomicLong());
        if (script.contains("INCRBY")) {
            long inc = (args == null || args.isEmpty()) ? 0L : Long.parseLong(args.get(0));
            return counter.addAndGet(inc);
        }
        if (script.contains("INCR")) {
            long current = counter.incrementAndGet();
            long limit = (args == null || args.isEmpty()) ? Long.MAX_VALUE : Long.parseLong(args.get(0));
            return current > limit ? 0L : 1L;
        }
        return counter.get();
    }

    private static Object defaultValue(Class<?> t) {
        if (t == long.class || t == Long.class) return 0L;
        if (t == int.class || t == Integer.class) return 0;
        if (t == boolean.class || t == Boolean.class) return false;
        if (t == double.class || t == Double.class) return 0.0d;
        return null;
    }
}
