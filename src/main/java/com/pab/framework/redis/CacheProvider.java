package com.pab.framework.redis;

import redis.clients.jedis.params.SetParams;

/**
 * 内网 Redis 客户端抽象接口（由 com.pab.framework:redis 提供）。
 *
 * <p><b>本地兼容说明：</b>此文件为本地编译/运行用的精简桩，仅保留本项目实际调用的方法。
 * 合入内网项目时请删除本桩，改用 com.pab.framework:redis 提供的完整 {@code CacheProvider}。
 * 因此<b>本桩里的方法名与签名必须与内网 SDK 保持一致</b>——这里用的都是 Redis 原生命令的
 * 标准命名（get / setex / del / incr / incrBy / expire / set），删桩后无需改调用方。
 *
 * <p><b>签名依据（2026-08-24 用户确认）：内网 CacheProvider 底层就是 jedis。</b>
 * 故本桩三个新增方法直接照抄 Jedis 的签名：{@code Long incr(String)}、
 * {@code Long incrBy(String, long)}、{@code Long expire(String, int)}——
 * 返回 {@code Long} 而不是 {@code long}、{@code expire} 的 TTL 用 {@code int} 而不是 {@code long}，
 * 都是为了和 jedis 3.x 逐字对齐，合入内网时不会因为拆装箱或宽化转换而对不上。
 *
 * <p><b>⚠️ 禁止再引入 {@code eval} / Lua：</b>内网 Redis 已确认<b>禁用 Lua 脚本</b>
 * （2026-08-24 内网验证结论）。原先的 {@code eval(script, keys, args)} 及其 EVALSHA 优化已整体移除，
 * 配额计数改用 INCR / INCRBY / EXPIRE 原生命令。任何"用一段小脚本保证原子性"的想法在本项目都行不通。
 */
public interface CacheProvider {

    /** GET key，不存在返回 null */
    String get(String key);

    /** SETEX key seconds value */
    String setex(String key, int seconds, String value);

    /** DEL key，返回删除数量 */
    Long del(String key);

    /**
     * INCR key，返回自增后的值。key 不存在时按 0 起算，即首次调用返回 1。
     *
     * <p>INCR 单命令本身即原子，多实例并发计数不会丢失——这是去掉 Lua 之后
     * 配额计数仍然准确的根本依据。
     */
    Long incr(String key);

    /** INCRBY key increment，返回自增后的值。用于月度 Token 累加。 */
    Long incrBy(String key, long increment);

    /** EXPIRE key seconds，设置成功返回 1、key 不存在返回 0。 */
    Long expire(String key, int seconds);

    /**
     * {@code SET key value [NX] [EX ttl]}，加锁成功返回 {@code "OK"}。
     * 用于 DynamicTaskScheduler 的分布式锁竞争。
     */
    String set(String key, String value, SetParams setParams);

}
