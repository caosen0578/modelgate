package com.pab.framework.redis;

import redis.clients.jedis.BitOP;
import redis.clients.jedis.GeoCoordinate;
import redis.clients.jedis.GeoRadiusResponse;
import redis.clients.jedis.GeoUnit;
import redis.clients.jedis.JedisPubSub;
import redis.clients.jedis.BinaryJedisPubSub;
import redis.clients.jedis.ListPosition;
import redis.clients.jedis.ScanParams;
import redis.clients.jedis.ScanResult;
import redis.clients.jedis.SortingParams;
import redis.clients.jedis.StreamEntry;
import redis.clients.jedis.StreamEntryID;
import redis.clients.jedis.StreamPendingEntry;
import redis.clients.jedis.Tuple;
import redis.clients.jedis.params.SetParams;
import redis.clients.jedis.params.ZAddParams;
import redis.clients.jedis.params.ZIncrByParams;
import redis.clients.jedis.params.ZParams;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 内网 Redis 客户端抽象接口（由 com.pab.framework:redis 提供）。
 * 此文件为本地编译用源码桩，方法签名来自 CacheProvider.class 反编译。
 */
public interface CacheProvider {

    // ===== Key =====

    Long del(String key);
    Long del(String... keys);
    Long del(byte[] key);
    Long del(byte[]... keys);
    Long unlink(String key);
    Long unlink(String... keys);
    Long unlink(byte[] key);
    Long unlink(byte[]... keys);
    Boolean exists(String key);
    Boolean exists(byte[] key);
    Long exists(String... keys);
    Long exists(byte[]... keys);
    Long expire(String key, int seconds);
    Long expire(byte[] key, int seconds);
    Long expireAt(String key, long unixTime);
    Long expireAt(byte[] key, long unixTime);
    Long pexpire(String key, long milliseconds);
    Long pexpire(byte[] key, long milliseconds);
    Long pexpireAt(String key, long millisecondsTimestamp);
    Long pexpireAt(byte[] key, long millisecondsTimestamp);
    Long ttl(String key);
    Long ttl(byte[] key);
    Long pttl(String key);
    Long pttl(byte[] key);
    Long persist(String key);
    Long persist(byte[] key);
    String type(String key);
    String type(byte[] key);
    String rename(String oldkey, String newkey);
    String rename(byte[] oldkey, byte[] newkey);
    Long renamenx(String oldkey, String newkey);
    Long renamenx(byte[] oldkey, byte[] newkey);
    Set<String> keys(String pattern);
    Set<byte[]> keys(byte[] pattern);
    ScanResult<String> scan(String cursor, ScanParams scanParams);
    ScanResult<byte[]> scan(byte[] cursor, ScanParams scanParams);
    byte[] dump(String key);
    byte[] dump(byte[] key);
    String restore(String key, int ttl, byte[] serializedValue);
    String restore(byte[] key, int ttl, byte[] serializedValue);
    Long touch(String key);
    Long touch(String... keys);
    Long touch(byte[] key);
    Long touch(byte[]... keys);

    // ===== String =====

    String get(String key);
    byte[] get(byte[] key);
    String set(String key, String value);
    String set(byte[] key, byte[] value);
    String set(String key, String value, SetParams setParams);
    String set(byte[] key, byte[] value, SetParams setParams);
    String setex(String key, int seconds, String value);
    String setex(byte[] key, int seconds, byte[] value);
    String psetex(String key, long milliseconds, String value);
    String psetex(byte[] key, long milliseconds, byte[] value);
    Long setnx(String key, String value);
    Long setnx(byte[] key, byte[] value);
    String setrange(String key, long offset, String value);
    Long setrange(byte[] key, long offset, byte[] value);
    String getrange(String key, long startOffset, long endOffset);
    byte[] getrange(byte[] key, long startOffset, long endOffset);
    String getSet(String key, String value);
    byte[] getSet(byte[] key, byte[] value);
    Long incr(String key);
    Long incr(byte[] key);
    Long incrBy(String key, long increment);
    Long incrBy(byte[] key, long increment);
    Double incrByFloat(String key, double increment);
    Double incrByFloat(byte[] key, double increment);
    Long decr(String key);
    Long decr(byte[] key);
    Long decrBy(String key, long decrement);
    Long decrBy(byte[] key, long decrement);
    Long append(String key, String value);
    Long append(byte[] key, byte[] value);
    Long strlen(String key);
    Long strlen(byte[] key);
    String substr(String key, int start, int end);
    byte[] substr(byte[] key, int start, int end);
    List<String> mget(String... keys);
    List<byte[]> mget(byte[]... keys);
    String mset(String... keysvalues);
    String mset(byte[]... keysvalues);
    Long msetnx(String... keysvalues);

    // ===== Bit =====

    Boolean setbit(String key, long offset, boolean value);
    Boolean setbit(String key, long offset, String bitValue);
    Boolean setbit(byte[] key, long offset, boolean value);
    Boolean setbit(byte[] key, long offset, byte[] bitValue);
    Boolean getbit(String key, long offset);
    Boolean getbit(byte[] key, long offset);
    Long bitcount(String key);
    Long bitcount(byte[] key);
    Long bitcount(String key, long start, long end);
    Long bitcount(byte[] key, long start, long end);
    Long bitop(BitOP op, String destKey, String... srcKeys);
    Long bitop(BitOP op, byte[] destKey, byte[]... srcKeys);
    List<Long> bitfield(String key, String... arguments);
    List<Long> bitfield(byte[] key, byte[]... arguments);

    // ===== List =====

    Long lpush(String key, String... values);
    Long lpush(byte[] key, byte[]... values);
    Long rpush(String key, String... values);
    Long rpush(byte[] key, byte[]... values);
    Long lpushx(String key, String... values);
    Long lpushx(byte[] key, byte[]... values);
    Long rpushx(String key, String... values);
    Long rpushx(byte[] key, byte[]... values);
    String lpop(String key);
    byte[] lpop(byte[] key);
    String rpop(String key);
    byte[] rpop(byte[] key);
    String rpoplpush(String srckey, String dstkey);
    byte[] rpoplpush(byte[] srckey, byte[] dstkey);
    String brpoplpush(String source, String destination, int timeout);
    byte[] brpoplpush(byte[] source, byte[] destination, int timeout);
    List<String> blpop(int timeout, String... keys);
    List<byte[]> blpop(int timeout, byte[]... keys);
    List<String> blpop(int timeout, String key);
    List<String> brpop(int timeout, String... keys);
    List<byte[]> brpop(int timeout, byte[]... keys);
    List<String> brpop(int timeout, String key);
    Long llen(String key);
    Long llen(byte[] key);
    String lindex(String key, long index);
    byte[] lindex(byte[] key, long index);
    String lset(String key, long index, String value);
    String lset(byte[] key, long index, byte[] value);
    Long linsert(String key, ListPosition where, String pivot, String value);
    Long linsert(byte[] key, ListPosition where, byte[] pivot, byte[] value);
    List<String> lrange(String key, long start, long stop);
    List<byte[]> lrange(byte[] key, long start, long stop);
    Long lrem(String key, long count, String value);
    Long lrem(byte[] key, long count, byte[] value);
    String ltrim(String key, long start, long stop);
    String ltrim(byte[] key, long start, long stop);

    // ===== Set =====

    Long sadd(String key, String... members);
    Long sadd(byte[] key, byte[]... members);
    Long srem(String key, String... members);
    Long srem(byte[] key, byte[]... members);
    Long scard(String key);
    Long scard(byte[] key);
    Boolean sismember(String key, String member);
    Boolean sismember(byte[] key, byte[] member);
    Set<String> smembers(String key);
    Set<byte[]> smembers(byte[] key);
    String spop(String key);
    byte[] spop(byte[] key);
    Set<String> spop(String key, long count);
    Set<byte[]> spop(byte[] key, long count);
    String srandmember(String key);
    byte[] srandmember(byte[] key);
    List<String> srandmember(String key, int count);
    List<byte[]> srandmember(byte[] key, int count);
    Long smove(String srckey, String dstkey, String member);
    Long smove(byte[] srckey, byte[] dstkey, byte[] member);
    Set<String> sunion(String... keys);
    Set<byte[]> sunion(byte[]... keys);
    Long sunionstore(String dstkey, String... keys);
    Long sunionstore(byte[] dstkey, byte[]... keys);
    Set<String> sinter(String... keys);
    Set<byte[]> sinter(byte[]... keys);
    Long sinterstore(String dstkey, String... keys);
    Long sinterstore(byte[] dstkey, byte[]... keys);
    Set<String> sdiff(String... keys);
    Set<byte[]> sdiff(byte[]... keys);
    Long sdiffstore(String dstkey, String... keys);
    Long sdiffstore(byte[] dstkey, byte[]... keys);
    ScanResult<String> sscan(String key, String cursor);
    ScanResult<byte[]> sscan(byte[] key, byte[] cursor);
    ScanResult<String> sscan(byte[] key, byte[] cursor, ScanParams scanParams);

    // ===== Sorted Set =====

    Long zadd(String key, double score, String member);
    Long zadd(byte[] key, double score, byte[] member);
    Long zadd(String key, Map<String, Double> scoreMembers);
    Long zadd(byte[] key, Map<byte[], Double> scoreMembers);
    Long zadd(String key, double score, String member, ZAddParams params);
    Long zadd(String key, Map<String, Double> scoreMembers, ZAddParams params);
    Long zadd(byte[] key, double score, byte[] member, ZAddParams params);
    Long zadd(byte[] key, Map<byte[], Double> scoreMembers, ZAddParams params);
    Long zcard(String key);
    Long zcard(byte[] key);
    Double zscore(String key, String member);
    Double zscore(byte[] key, byte[] member);
    Long zrank(String key, String member);
    Long zrank(byte[] key, byte[] member);
    Long zrevrank(String key, String member);
    Long zrevrank(byte[] key, byte[] member);
    Long zrem(String key, String... members);
    Long zrem(byte[] key, byte[]... members);
    Double zincrby(String key, double increment, String member);
    Double zincrby(byte[] key, double increment, byte[] member);
    Double zincrby(String key, double increment, String member, ZIncrByParams params);
    Double zincrby(byte[] key, double increment, byte[] member, ZIncrByParams params);
    Long zcount(String key, double min, double max);
    Long zcount(byte[] key, double min, double max);
    Long zcount(String key, String min, String max);
    Long zcount(byte[] key, byte[] min, byte[] max);
    Set<String> zrange(String key, long start, long stop);
    Set<byte[]> zrange(byte[] key, long start, long stop);
    Set<String> zrevrange(String key, long start, long stop);
    Set<byte[]> zrevrange(byte[] key, long start, long stop);
    Set<String> zrangeByScore(String key, double min, double max);
    Set<String> zrangeByScore(String key, String min, String max);
    Set<byte[]> zrangeByScore(byte[] key, double min, double max);
    Set<byte[]> zrangeByScore(byte[] key, byte[] min, byte[] max);
    Set<String> zrangeByScore(String key, double min, double max, int offset, int count);
    Set<String> zrangeByScore(String key, String min, String max, int offset, int count);
    Set<byte[]> zrangeByScore(byte[] key, double min, double max, int offset, int count);
    Set<byte[]> zrangeByScore(byte[] key, byte[] min, byte[] max, int offset, int count);
    Set<Tuple> zrangeByScoreWithScores(String key, double min, double max);
    Set<Tuple> zrangeByScoreWithScores(String key, String min, String max);
    Set<Tuple> zrangeByScoreWithScores(byte[] key, double min, double max);
    Set<Tuple> zrangeByScoreWithScores(byte[] key, byte[] min, byte[] max);
    Set<Tuple> zrangeByScoreWithScores(String key, double min, double max, int offset, int count);
    Set<Tuple> zrangeByScoreWithScores(String key, String min, String max, int offset, int count);
    Set<Tuple> zrangeByScoreWithScores(byte[] key, double min, double max, int offset, int count);
    Set<Tuple> zrangeByScoreWithScores(byte[] key, byte[] min, byte[] max, int offset, int count);
    Set<Tuple> zrangeWithScores(String key, long start, long stop);
    Set<Tuple> zrangeWithScores(byte[] key, long start, long stop);
    Set<String> zrevrangeByScore(String key, double max, double min);
    Set<String> zrevrangeByScore(String key, String max, String min);
    Set<byte[]> zrevrangeByScore(byte[] key, double max, double min);
    Set<byte[]> zrevrangeByScore(byte[] key, byte[] max, byte[] min);
    Set<String> zrevrangeByScore(String key, double max, double min, int offset, int count);
    Set<String> zrevrangeByScore(String key, String max, String min, int offset, int count);
    Set<byte[]> zrevrangeByScore(byte[] key, double max, double min, int offset, int count);
    Set<byte[]> zrevrangeByScore(byte[] key, byte[] max, byte[] min, int offset, int count);
    Set<Tuple> zrevrangeByScoreWithScores(String key, double max, double min);
    Set<Tuple> zrevrangeByScoreWithScores(String key, String max, String min);
    Set<Tuple> zrevrangeByScoreWithScores(byte[] key, double max, double min);
    Set<Tuple> zrevrangeByScoreWithScores(byte[] key, byte[] max, byte[] min);
    Set<Tuple> zrevrangeByScoreWithScores(String key, double max, double min, int offset, int count);
    Set<Tuple> zrevrangeByScoreWithScores(String key, String max, String min, int offset, int count);
    Set<Tuple> zrevrangeByScoreWithScores(byte[] key, double max, double min, int offset, int count);
    Set<Tuple> zrevrangeByScoreWithScores(byte[] key, byte[] max, byte[] min, int offset, int count);
    Set<Tuple> zrevrangeWithScores(String key, long start, long stop);
    Set<Tuple> zrevrangeWithScores(byte[] key, long start, long stop);
    Set<String> zrangeByLex(String key, String min, String max);
    Set<byte[]> zrangeByLex(byte[] key, byte[] min, byte[] max);
    Set<String> zrangeByLex(String key, String min, String max, int offset, int count);
    Set<byte[]> zrangeByLex(byte[] key, byte[] min, byte[] max, int offset, int count);
    Set<String> zrevrangeByLex(String key, String max, String min);
    Set<byte[]> zrevrangeByLex(byte[] key, byte[] max, byte[] min);
    Set<String> zrevrangeByLex(String key, String max, String min, int offset, int count);
    Set<byte[]> zrevrangeByLex(byte[] key, byte[] max, byte[] min, int offset, int count);
    Long zremrangeByRank(String key, long start, long stop);
    Long zremrangeByRank(byte[] key, long start, long stop);
    Long zremrangeByScore(String key, double min, double max);
    Long zremrangeByScore(String key, String min, String max);
    Long zremrangeByScore(byte[] key, double min, double max);
    Long zremrangeByScore(byte[] key, byte[] min, byte[] max);
    Long zremrangeByLex(String key, String min, String max);
    Long zremrangeByLex(byte[] key, byte[] min, byte[] max);
    Long zlexcount(String key, String min, String max);
    Long zlexcount(byte[] key, byte[] min, byte[] max);
    Long sort(String key, String dstkey);
    Long sort(byte[] key, byte[] dstkey);
    List<String> sort(String key);
    List<byte[]> sort(byte[] key);
    List<String> sort(String key, SortingParams sortingParams);
    List<byte[]> sort(byte[] key, SortingParams sortingParams);
    Long sort(String key, SortingParams sortingParams, String dstkey);
    Long sort(byte[] key, SortingParams sortingParams, byte[] dstkey);
    Long zinterstore(String dstkey, String... sets);
    Long zinterstore(byte[] dstkey, byte[]... sets);
    Long zinterstore(String dstkey, ZParams params, String... sets);
    Long zinterstore(byte[] dstkey, ZParams params, byte[]... sets);
    Long zunionstore(String dstkey, String... sets);
    Long zunionstore(byte[] dstkey, byte[]... sets);
    Long zunionstore(String dstkey, ZParams params, String... sets);
    Long zunionstore(byte[] dstkey, ZParams params, byte[]... sets);
    ScanResult<Tuple> zscan(String key, String cursor);
    ScanResult<Tuple> zscan(byte[] key, byte[] cursor);
    ScanResult<Tuple> zscan(byte[] key, byte[] cursor, ScanParams scanParams);

    // ===== Hash =====

    Long hset(String key, String field, String value);
    Long hset(byte[] key, byte[] field, byte[] value);
    Long hset(String key, Map<String, String> hash);
    Long hmset(String key, Map<String, String> hash);
    Long hmset(byte[] key, Map<byte[], byte[]> hash);
    Long hsetnx(String key, String field, String value);
    Long hsetnx(byte[] key, byte[] field, byte[] value);
    String hget(String key, String field);
    byte[] hget(byte[] key, byte[] field);
    List<String> hmget(String key, String... fields);
    List<byte[]> hmget(byte[] key, byte[]... fields);
    Map<String, String> hgetAll(String key);
    Map<byte[], byte[]> hgetAll(byte[] key);
    Long hdel(String key, String... fields);
    Long hdel(byte[] key, byte[]... fields);
    Boolean hexists(String key, String field);
    Boolean hexists(byte[] key, byte[] field);
    Long hlen(String key);
    Long hlen(byte[] key);
    Set<String> hkeys(String key);
    Set<byte[]> hkeys(byte[] key);
    List<String> hvals(String key);
    Collection<byte[]> hvals(byte[] key);
    Long hincrBy(String key, String field, long value);
    Long hincrBy(byte[] key, byte[] field, long value);
    Double hincrByFloat(String key, String field, double value);
    Double hincrByFloat(byte[] key, byte[] field, double value);
    String hstrlen(String key, String field);
    Long hstrlen(byte[] key, byte[] field);
    ScanResult<Map.Entry<String, String>> hscan(String key, String cursor);
    ScanResult<Map.Entry<byte[], byte[]>> hscan(byte[] key, byte[] cursor);
    ScanResult<Map.Entry<byte[], byte[]>> hscan(byte[] key, byte[] cursor, ScanParams scanParams);

    // ===== HyperLogLog =====

    Long pfadd(String key, String... elements);
    Long pfadd(byte[] key, byte[]... elements);
    long pfcount(String key);
    long pfcount(byte[]... keys);
    long pfcount(String... keys);
    String pfmerge(String destkey, String... sourcekeys);
    String pfmerge(byte[] destkey, byte[]... sourcekeys);

    // ===== Geo =====

    Long geoadd(String key, double longitude, double latitude, String member);
    Long geoadd(byte[] key, double longitude, double latitude, byte[] member);
    Long geoadd(String key, Map<String, GeoCoordinate> memberCoordinateMap);
    Long geoadd(byte[] key, Map<byte[], GeoCoordinate> memberCoordinateMap);
    Double geodist(String key, String member1, String member2);
    Double geodist(byte[] key, byte[] member1, byte[] member2);
    Double geodist(String key, String member1, String member2, GeoUnit unit);
    Double geodist(byte[] key, byte[] member1, byte[] member2, GeoUnit unit);
    List<String> geohash(String key, String... members);
    List<byte[]> geohash(byte[] key, byte[]... members);
    List<GeoCoordinate> geopos(String key, String... members);
    List<GeoCoordinate> geopos(byte[] key, byte[]... members);
    List<GeoRadiusResponse> georadius(String key, double longitude, double latitude, double radius, GeoUnit unit);
    List<GeoRadiusResponse> georadius(byte[] key, double longitude, double latitude, double radius, GeoUnit unit);
    List<GeoRadiusResponse> georadiusReadonly(String key, double longitude, double latitude, double radius, GeoUnit unit);
    List<GeoRadiusResponse> georadiusReadonly(byte[] key, double longitude, double latitude, double radius, GeoUnit unit);
    List<GeoRadiusResponse> georadiusByMember(String key, String member, double radius, GeoUnit unit);
    List<GeoRadiusResponse> georadiusByMember(byte[] key, byte[] member, double radius, GeoUnit unit);
    List<GeoRadiusResponse> georadiusByMemberReadonly(String key, String member, double radius, GeoUnit unit);
    List<GeoRadiusResponse> georadiusByMemberReadonly(byte[] key, byte[] member, double radius, GeoUnit unit);
    List<GeoRadiusResponse> georadiusByMemberReadonly(String key, String member, double radius, GeoUnit geoUnit);
    List<GeoRadiusResponse> georadiusByMemberReadonly(byte[] key, byte[] member, double radius, GeoUnit geoUnit);

    // ===== Pub/Sub =====

    Long publish(String channel, String message);
    Long publish(byte[] channel, byte[] message);
    void subscribe(JedisPubSub jedisPubSub, String... channels);
    void subscribe(BinaryJedisPubSub jedisPubSub, byte[]... channels);
    void psubscribe(JedisPubSub jedisPubSub, String... patterns);
    void psubscribe(BinaryJedisPubSub jedisPubSub, byte[]... patterns);

    // ===== Stream =====

    StreamEntryID xadd(String key, StreamEntryID id, Map<String, String> hash);
    byte[] xadd(byte[] key, byte[] id, Map<byte[], byte[]> hash);
    StreamEntryID xadd(String key, StreamEntryID id, Map<String, String> hash, long maxLen, boolean approximateLength);
    Long xlen(String key);
    Long xlen(byte[] key);
    List<StreamEntry> xrange(String key, StreamEntryID start, StreamEntryID end, int count);
    List<byte[]> xrange(byte[] key, byte[] start, byte[] end, int count);
    List<StreamEntry> xrevrange(String key, StreamEntryID end, StreamEntryID start, int count);
    List<byte[]> xrevrange(byte[] key, byte[] end, byte[] start, int count);
    List<Map.Entry<String, List<StreamEntry>>> xread(int count, long block, Map<String, StreamEntryID> streams);
    List<Map.Entry<byte[], List<byte[]>>> xread(int count, long block, Map<byte[], byte[]> streams);
    List<Map.Entry<String, List<StreamEntry>>> xreadGroup(String groupname, String consumer, int count, long block, boolean noAck, Map<String, StreamEntryID> streams);
    Long xack(String key, String group, StreamEntryID... ids);
    Long xack(byte[] key, byte[] group, byte[]... ids);
    String xgroupCreate(String key, String groupname, StreamEntryID id, boolean makeStream);
    String xgroupCreate(byte[] key, byte[] groupname, byte[] id, boolean makeStream);
    Long xgroupDestroy(String key, String groupname);
    Long xgroupDestroy(byte[] key, byte[] groupname);
    String xgroupSetID(String key, String groupname, StreamEntryID id);
    String xgroupSetID(byte[] key, byte[] groupname, byte[] id);
    String xgroupDelConsumer(String key, String groupname, String consumername);
    String xgroupDelConsumer(byte[] key, byte[] groupname, byte[] consumername);
    Long xdel(String key, StreamEntryID... ids);
    Long xdel(byte[] key, byte[]... ids);
    Long xtrim(String key, long maxLen, boolean approximateLength);
    Long xtrim(byte[] key, long maxLen, boolean approximateLength);
    List<StreamPendingEntry> xpending(String key, String groupname, StreamEntryID start, StreamEntryID end, int count, String consumername);
    List<byte[]> xpending(byte[] key, byte[] groupname, byte[] start, byte[] end, int count, byte[] consumername);
    List<StreamEntry> xclaim(String key, String group, String consumername, long minIdleTime, long newIdleTime, int retries, boolean force, StreamEntryID... ids);
    List<byte[]> xclaim(byte[] key, byte[] group, byte[] consumername, long minIdleTime, long newIdleTime, int retries, boolean force, byte[]... ids);
    StreamEntryID xack(String key, String group, StreamEntryID id);

    // ===== Script =====

    Object eval(String script, String keyCount, String... params);
    Object eval(String script, int keyCount, String... params);
    Object eval(String script, List<String> keys, List<String> args);

    // ===== Lock =====

    boolean lock(String key, int expireSeconds);
    boolean unLock(String key);

    // ===== Misc =====

    String echo(String message);
    byte[] echo(byte[] message);
    Long waitReplicas(int replicas, long timeout);
    Long waitReplicas(byte[] key, int replicas, long timeout);
    void wait(long timeoutMillis);
    void wait(long timeoutMillis, int nanos);
    void close();
}
