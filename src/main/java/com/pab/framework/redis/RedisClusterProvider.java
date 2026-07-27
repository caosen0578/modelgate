package com.pab.framework.redis;

/**
 * 内网 Redis 集群 Provider 工厂（由 com.pab.framework:redis 提供）。
 *
 * <p><b>本地兼容说明：</b>此文件为本地编译用的桩。仅在非 local Profile 的 {@code RedisConfig}
 * 中引用；local Profile 使用内存版 {@code CacheProvider}，不会走到这里。
 * 合入内网项目时删除本桩，改用真实实现。
 */
public final class RedisClusterProvider {

    private RedisClusterProvider() {
    }

    public static CacheProvider getInstance(String clusterKey) {
        throw new UnsupportedOperationException(
                "RedisClusterProvider 为本地编译桩，非 local Profile 需由 com.pab.framework:redis 提供真实实现");
    }
}
