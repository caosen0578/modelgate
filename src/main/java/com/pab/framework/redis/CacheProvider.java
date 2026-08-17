package com.pab.framework.redis;

import redis.clients.jedis.params.SetParams;

import java.util.List;

/**
 * 内网 Redis 客户端抽象接口（由 com.pab.framework:redis 提供）。
 *
 * <p><b>本地兼容说明：</b>此文件为本地编译/运行用的精简桩，仅保留本项目实际调用的方法。
 * 合入内网项目时请删除本桩，改用 com.pab.framework:redis 提供的完整 {@code CacheProvider}。
 */
public interface CacheProvider {

    /** GET key，不存在返回 null */
    String get(String key);

    /** SETEX key seconds value */
    String setex(String key, int seconds, String value);

    /** DEL key，返回删除数量 */
    Long del(String key);

    /**
     * EVAL Lua 脚本。
     * @param script Lua 脚本
     * @param keys   KEYS 列表
     * @param args   ARGV 列表
     * @return 脚本返回值（限流脚本返回 Long）
     */
    Object eval(String script, List<String> keys, List<String> args);

    /**
     * {@code SET key value [NX] [EX ttl]}，加锁成功返回 {@code "OK"}。
     * 用于 DynamicTaskScheduler 的分布式锁竞争。
     */
    String set(String key, String value, SetParams setParams);

}
