package com.pab.ficc.ibp.modelgate.server.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.pab.ficc.ibp.modelgate.server.domain.entity.AppCredentialEntity;
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
 * </ul>
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

    /** 凭证缓存：key = SHA-256(api_key)，最多缓存 10,000 条（约覆盖 1 万个凭证） */
    private final Cache<String, Optional<AppCredentialEntity>> credentialCache = Caffeine.newBuilder()
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

    // ===== 凭证缓存（L1 → DB） =====

    /**
     * 按哈希查凭证，L1 命中直接返回，L1 miss 则调用 dbLoader 查 DB 并回填 L1。
     *
     * <p>注意：此处 L1 miss 后直接查 DB，不经过 Redis L2。
     * 凭证的 Redis 层（{@link #setRedisCredential}）目前仅作备份，
     * 主读路径为 Caffeine → DB。
     */
    public Optional<AppCredentialEntity> getCredential(String secretHash, Supplier<Optional<AppCredentialEntity>> dbLoader) {
        Optional<AppCredentialEntity> cached = credentialCache.getIfPresent(secretHash);
        if (cached != null) return cached;
        Optional<AppCredentialEntity> result = dbLoader.get();
        // 只缓存命中结果；不缓存空结果，防止攻击者用大量无效 key 填满 10k 上限逐出合法凭证
        if (result.isPresent()) {
            credentialCache.put(secretHash, result);
        }
        return result;
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
        credentialCache.asMap().entrySet().removeIf(e ->
                e.getValue().isPresent() && appId.equals(e.getValue().get().getAppId()));
    }

    // ===== 模型授权缓存（L1 → DB） =====

    /** 按 appId 查该 App 的所有模型授权，L1 命中直接返回，miss 则查 DB 并回填。 */
    public List<AppModelAuthEntity> getModelAuths(String appId, Supplier<List<AppModelAuthEntity>> dbLoader) {
        List<AppModelAuthEntity> cached = modelAuthCache.getIfPresent(appId);
        if (cached != null) return cached;
        List<AppModelAuthEntity> result = dbLoader.get();
        modelAuthCache.put(appId, result);
        return result;
    }

    /** 失效某 App 的模型授权缓存（授权增删改时调用）。 */
    public void evictModelAuthCache(String appId) {
        modelAuthCache.invalidate(appId);
        evictRedisKey("mg:auth:" + appId);
    }

    // ===== 访问规则缓存（仅 L1，不存 Redis） =====

    public List<AccessRuleEntity> getAccessRules(String appId, Supplier<List<AccessRuleEntity>> dbLoader) {
        List<AccessRuleEntity> cached = accessRuleCache.getIfPresent(appId);
        if (cached != null) return cached;
        List<AccessRuleEntity> result = dbLoader.get();
        accessRuleCache.put(appId, result);
        return result;
    }

    public void evictAccessRuleCache(String appId) {
        accessRuleCache.invalidate(appId);
    }

    // ===== 模型配置缓存（仅 L1，不存 Redis） =====

    public Optional<ModelInstanceEntity> getModel(String modelId, Supplier<Optional<ModelInstanceEntity>> dbLoader) {
        Optional<ModelInstanceEntity> cached = modelCache.getIfPresent(modelId);
        if (cached != null) return cached;
        Optional<ModelInstanceEntity> result = dbLoader.get();
        modelCache.put(modelId, result);
        return result;
    }

    public void evictModelCache(String modelId) {
        modelCache.invalidate(modelId);
    }

    public void evictAllModelCache() {
        modelCache.invalidateAll();
    }

    // ===== 上游凭证缓存（已解密，仅 L1，不存 Redis，TTL=30s） =====

    public Optional<ModelCredentialEntity> getModelCredential(String modelId, Supplier<Optional<ModelCredentialEntity>> dbLoader) {
        Optional<ModelCredentialEntity> cached = modelCredentialCache.getIfPresent(modelId);
        if (cached != null) return cached;
        Optional<ModelCredentialEntity> result = dbLoader.get();
        modelCredentialCache.put(modelId, result);
        return result;
    }

    public void evictModelCredentialCache(String modelId) {
        modelCredentialCache.invalidate(modelId);
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
