package com.pab.ficc.ibp.modelgate.server.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.pab.ficc.ibp.modelgate.server.domain.entity.AppCredentialEntity;
import com.pab.ficc.ibp.modelgate.server.domain.entity.AppInfoEntity;
import com.pab.ficc.ibp.modelgate.server.domain.entity.AppModelAuthEntity;
import com.pab.ficc.ibp.modelgate.server.domain.entity.AccessRuleEntity;
import com.pab.ficc.ibp.modelgate.server.domain.entity.ModelCredentialEntity;
import com.pab.ficc.ibp.modelgate.server.domain.entity.ModelInstanceEntity;
import com.pab.framework.redis.CacheProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 网关两级缓存统一管理器（Caffeine L1 + Redis L2）。
 *
 * <p><b>缓存分层设计：</b>
 * <pre>
 *   请求 → Caffeine L1（本进程，纳秒级）→ Redis L2（集群共享，毫秒级）→ MySQL（毫秒~十毫秒）
 * </pre>
 *
 * <p><b>各缓存说明：</b>
 * <ul>
 *   <li><b>凭证（Credential）</b>：L1 60s TTL + Redis 30天 TTL。
 *       每次 Bearer 请求都查此缓存，命中率最高，对延迟最敏感。
 *       写操作后必须主动失效，否则禁用凭证要等 60s 才生效。</li>
 *   <li><b>模型授权（ModelAuth）</b>：L1 60s TTL + Redis 失效通知。
 *       按 appId 聚合，一个 App 的所有模型授权同批加载，减少 DB 查询次数。</li>
 *   <li><b>访问规则（AccessRuleEntity）</b>：L1 60s TTL，不存 Redis（数据量小，变更少）。</li>
 *   <li><b>模型配置（ModelInstanceEntity）</b>：L1 30s TTL，不存 Redis。
 *       30s 内上游地址修改自动生效，对路由实时性要求高但 Redis 收益低。</li>
 *   <li><b>接入系统信息（AppInfoEntity）</b>：L1 60s TTL，不存 Redis（2026-08-18 压测优化新增，
 *       原先 {@code CredentialAuthFilter} 里这一查是故意不缓存的，见 {@code appInfoCache} 字段注释）。</li>
 * </ul>
 *
 * <p><b>原子加载（2026-08-18 压测优化）</b>：全部 {@code get*} 方法均用 Caffeine 的
 * {@code Cache.get(key, mappingFunction)} 而非手工 {@code getIfPresent}+{@code put}——
 * 后者在缓存过期瞬间，多个并发请求会同时判定 miss、同时打 DB（缓存雪崩）；前者对同一 key 的
 * 并发 miss 只执行一次 mappingFunction，其余并发调用等待复用结果。
 *
 * <p><b>降级策略：</b>Redis 故障时，L1 已缓存的数据继续提供服务；
 * L1 miss 时不穿透 DB（直接拒绝），防止 Redis 故障触发雪崩。
 *
 * <p><b>主动失效：</b>写操作（增删改启禁）后调用对应 {@code evict*} 方法，
 * 同时清 L1 和 Redis，使变更在秒级内对所有实例生效。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GatewayCacheManager {

    private final CacheProvider cacheProvider;

    /** 凭证在 Redis 中保留 30 天，与凭证本身的生命周期匹配 */
    private static final int CREDENTIAL_REDIS_TTL_SECONDS = 30 * 24 * 3600;

    // ===== L1 Caffeine 本地缓存 =====

    /**
     * 凭证缓存：key = SHA-256(api_key)，最多缓存 10,000 条（约覆盖 1 万个凭证）。
     * 值类型为 {@code AppCredentialEntity}（非 Optional）——原子加载下 miss 结果不缓存，
     * 见 {@link #getCredential} 说明。
     */
    private final Cache<String, AppCredentialEntity> credentialCache = Caffeine.newBuilder()
            .expireAfterWrite(60, TimeUnit.SECONDS)
            .maximumSize(10_000)
            .build();

    /** 模型授权缓存：key = appId，value = 该 App 的所有模型授权列表 */
    private final Cache<String, List<AppModelAuthEntity>> modelAuthCache = Caffeine.newBuilder()
            .expireAfterWrite(60, TimeUnit.SECONDS)
            .maximumSize(5_000)
            .build();

    /** 访问规则缓存：key = appId，value = 该 App 的所有访问规则列表 */
    private final Cache<String, List<AccessRuleEntity>> accessRuleCache = Caffeine.newBuilder()
            .expireAfterWrite(60, TimeUnit.SECONDS)
            .maximumSize(5_000)
            .build();

    /** 模型配置缓存：key = modelId，TTL 30s（上游地址修改后 30s 内生效） */
    private final Cache<String, Optional<ModelInstanceEntity>> modelCache = Caffeine.newBuilder()
            .expireAfterWrite(30, TimeUnit.SECONDS)
            .maximumSize(1_000)
            .build();

    /**
     * 上游凭证缓存（含已解密 SK）：key = modelId，TTL 30s。
     * 与模型配置缓存保持相同 TTL，确保凭证更新后最多 30s 内对所有实例生效。
     * 写操作（applyCredentialUpdate）后主动 evict，变更即时生效。
     */
    private final Cache<String, Optional<ModelCredentialEntity>> modelCredentialCache = Caffeine.newBuilder()
            .expireAfterWrite(30, TimeUnit.SECONDS)
            .maximumSize(1_000)
            .build();

    /**
     * 接入系统信息缓存：key = appId，TTL 60s（与凭证/授权/访问规则缓存一致）。
     *
     * <p><b>2026-08-18 压测优化新增</b>：此前 {@code CredentialAuthFilter} 里的 {@code findByAppId}
     * 故意不走缓存，是为了在 {@code credentialCache}（60s TTL）之外再提供一层"App 禁用立即生效"的
     * 实时兜底——但代价是每个已鉴权请求都发一次同步 MySQL 查询，压测证实是并发升高时连接池被打满
     * 的主因之一（见 11-变更记录.md）。这里改为 evict-on-write（增删改禁用四处调用都会失效本缓存，
     * 与其它缓存同一套模式），TTL 只是兜底、不是主失效手段，理论上仍能满足"立即失效"要求；
     * 但相比此前"完全不缓存"，确实少了一层独立于其它缓存之外的实时兜底，是本轮明确接受的取舍。
     */
    private final Cache<String, AppInfoEntity> appInfoCache = Caffeine.newBuilder()
            .expireAfterWrite(60, TimeUnit.SECONDS)
            .maximumSize(5_000)
            .build();

    // ===== 凭证缓存（L1 → DB） =====

    /**
     * 按哈希查凭证，L1 命中直接返回，L1 miss 则调用 dbLoader 查 DB 并回填 L1。
     *
     * <p>注意：此处 L1 miss 后直接查 DB，不经过 Redis L2。
     * 凭证的 Redis 层（{@link #setRedisCredential}）目前仅作备份，
     * 主读路径为 Caffeine → DB。
     *
     * <p><b>原子加载（2026-08-18 压测优化）</b>：改用 {@code Cache.get(key, mappingFunction)}
     * 而非 {@code getIfPresent}+{@code put}，Caffeine 对同一 key 的并发 miss 只会执行一次
     * mappingFunction、其余并发调用等待复用结果——修复了"缓存过期瞬间多个并发请求同时打 DB"的
     * 雪崩问题。mappingFunction 返回 {@code null} 时 Caffeine 不会写入缓存，
     * 借此保留原有"不缓存空结果"的反刷库设计（防止攻击者用大量无效 key 填满 10k 上限逐出合法凭证）。
     */
    public Optional<AppCredentialEntity> getCredential(String secretHash, Supplier<Optional<AppCredentialEntity>> dbLoader) {
        AppCredentialEntity found = credentialCache.get(secretHash, k -> dbLoader.get().orElse(null));
        return Optional.ofNullable(found);
    }

    /** 失效单个凭证的 L1 缓存和 Redis 缓存（启用/禁用/删除时调用）。 */
    public void evictCredentialCache(String secretHash) {
        credentialCache.invalidate(secretHash);
        evictRedisKey("mg:cred:" + secretHash);
    }

    /**
     * 按 appId 批量失效凭证缓存（新建凭证或 App 状态变更时调用）。
     * 遍历 L1 缓存，移除属于该 App 的所有条目。
     */
    public void evictCredentialCacheByAppId(String appId) {
        credentialCache.asMap().entrySet().removeIf(e -> appId.equals(e.getValue().getAppId()));
    }

    // ===== 模型授权缓存（L1 → DB） =====

    /**
     * 按 appId 查该 App 的所有模型授权，L1 命中直接返回，miss 则查 DB 并回填。
     * 原子加载（{@code Cache.get(key, mappingFunction)}），并发 miss 只查一次 DB，见 {@link #getCredential}。
     */
    public List<AppModelAuthEntity> getModelAuths(String appId, Supplier<List<AppModelAuthEntity>> dbLoader) {
        return modelAuthCache.get(appId, k -> dbLoader.get());
    }

    /** 失效某 App 的模型授权缓存（授权增删改时调用）。 */
    public void evictModelAuthCache(String appId) {
        modelAuthCache.invalidate(appId);
        evictRedisKey("mg:auth:" + appId);
    }

    // ===== 访问规则缓存（仅 L1，不存 Redis） =====

    /** 原子加载，理由同 {@link #getCredential}。 */
    public List<AccessRuleEntity> getAccessRules(String appId, Supplier<List<AccessRuleEntity>> dbLoader) {
        return accessRuleCache.get(appId, k -> dbLoader.get());
    }

    public void evictAccessRuleCache(String appId) {
        accessRuleCache.invalidate(appId);
    }

    // ===== 模型配置缓存（仅 L1，不存 Redis） =====

    /** 原子加载，理由同 {@link #getCredential}；空结果（Optional.empty）本身也会被缓存，行为与此前一致。 */
    public Optional<ModelInstanceEntity> getModel(String modelId, Supplier<Optional<ModelInstanceEntity>> dbLoader) {
        return modelCache.get(modelId, k -> dbLoader.get());
    }

    public void evictModelCache(String modelId) {
        modelCache.invalidate(modelId);
    }

    public void evictAllModelCache() {
        modelCache.invalidateAll();
    }

    // ===== 上游凭证缓存（已解密，仅 L1，不存 Redis，TTL=30s） =====

    /** 原子加载，理由同 {@link #getCredential}；空结果（Optional.empty）本身也会被缓存，行为与此前一致。 */
    public Optional<ModelCredentialEntity> getModelCredential(String modelId, Supplier<Optional<ModelCredentialEntity>> dbLoader) {
        return modelCredentialCache.get(modelId, k -> dbLoader.get());
    }

    public void evictModelCredentialCache(String modelId) {
        modelCredentialCache.invalidate(modelId);
    }

    // ===== 接入系统信息缓存（仅 L1，不存 Redis，TTL=60s） =====

    /**
     * 按 appId 查接入系统信息，原子加载。dbLoader（{@code AppService.findByAppId}）在 appId
     * 不存在时抛 {@code BusinessException}——Caffeine 的 mappingFunction 抛异常时不会缓存该 key，
     * 异常原样向上抛出，行为与"不缓存"时一致，不会把 404 结果错误地缓存住。
     */
    public AppInfoEntity getAppInfo(String appId, Supplier<AppInfoEntity> dbLoader) {
        return appInfoCache.get(appId, k -> dbLoader.get());
    }

    /** 失效某 App 的接入系统信息缓存（增/改/启/禁/删时调用，见 AppService.evictAppScopedCache）。 */
    public void evictAppInfoCache(String appId) {
        appInfoCache.invalidate(appId);
    }

    // ===== Redis 工具方法 =====

    private void evictRedisKey(String key) {
        try {
            cacheProvider.del(key);
            log.debug("[Cache] Redis evicted key={}", key);
        } catch (Exception ex) {
            log.warn("[Cache] Redis evict failed key={}: {}", key, ex.getMessage());
        }
    }

    /** 将凭证 JSON 写入 Redis（TTL=30天），用于多实例间凭证数据共享备份。 */
    public void setRedisCredential(String secretHash, String json) {
        try {
            cacheProvider.setex("mg:cred:" + secretHash, CREDENTIAL_REDIS_TTL_SECONDS, json);
        } catch (Exception ex) {
            log.warn("[Cache] Redis set failed: {}", ex.getMessage());
        }
    }

    /** 从 Redis 读取凭证 JSON，Redis 故障时返回 {@code Optional.empty()} 而非抛异常。 */
    public Optional<String> getRedisCredential(String secretHash) {
        try {
            return Optional.ofNullable(cacheProvider.get("mg:cred:" + secretHash));
        } catch (Exception ex) {
            log.warn("[Cache] Redis get failed: {}", ex.getMessage());
            return Optional.empty();
        }
    }
}
