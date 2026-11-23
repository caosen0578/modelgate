package com.pab.ficc.ibp.modelgate.server.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.pab.ficc.ibp.modelgate.server.domain.entity.AppCredentialEntity;
import com.pab.ficc.ibp.modelgate.server.domain.entity.AppInfoEntity;
import com.pab.ficc.ibp.modelgate.server.domain.entity.AppModelAuthEntity;
import com.pab.ficc.ibp.modelgate.server.domain.entity.AccessRuleEntity;
import com.pab.ficc.ibp.modelgate.server.domain.entity.ModelCredentialEntity;
import com.pab.ficc.ibp.modelgate.server.domain.entity.AppServiceAuthEntity;
import com.pab.ficc.ibp.modelgate.server.domain.entity.ModelInstanceEntity;
import com.pab.ficc.ibp.modelgate.server.domain.entity.ServiceCredentialEntity;
import com.pab.ficc.ibp.modelgate.server.domain.entity.ServiceEntity;
import com.pab.framework.redis.CacheProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 网关缓存统一管理器（业务读取热路径以 Caffeine L1 为主）。
 *
 * <p><b>真实读取路径：</b>凭证、授权、访问规则、模型和接入系统信息均为
 * {@code Caffeine L1 → MySQL}。类中虽保留凭证 Redis set/get 辅助方法，但当前没有业务调用，
 * 因而并非有效 L2。模型授权和个人访问规则通过 Redis 版本标记协调本地缓存。
 *
 * <p><b>各缓存说明：</b>
 * <ul>
 *   <li><b>凭证（Credential）</b>：实际读取为 L1 60s TTL → MySQL；Redis 30 天辅助方法尚未接入。
 *       每次 Bearer 请求都查此缓存，命中率最高，对延迟最敏感。
 *       写操作后必须主动失效，否则禁用凭证要等 60s 才生效。</li>
 *   <li><b>模型授权（ModelAuth）</b>：按 appId 聚合缓存 60s，活跃 app 每实例每秒最多检查一次 Redis 版本。
 *       版本变化时合并同 appId 的并发回源并重新加载授权。</li>
 *   <li><b>访问规则（AccessRuleEntity）</b>：L1 60s TTL，个人访问规则检查共享版本。</li>
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
 * <p><b>降级策略：</b>授权详情从数据库加载。Redis 不可用时继续使用有效的本地快照；
 * L1 miss 会调用 DB loader，并由 Caffeine 按 key 合并并发回源。
 *
 * <p><b>主动失效：</b>写操作（增删改启禁）后调用对应 {@code evict*} 方法，当前实例立即生效。
 * 其他实例的模型授权和个人访问规则按共享版本收敛，其余缓存依靠各 L1 的 30/60 秒 TTL。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GatewayCacheManager {

    private final CacheProvider cacheProvider;

    /** 凭证在 Redis 中保留 30 天，与凭证本身的生命周期匹配 */
    private static final int CREDENTIAL_REDIS_TTL_SECONDS = 30 * 24 * 3600;
    private static final int RULE_TTL_SECONDS = 60;
    // 共享标记至少覆盖旧快照的存活期。过期后无需在 Redis 常驻每个 appId。
    private static final int RULE_REVISION_TTL_SECONDS = 2 * RULE_TTL_SECONDS;

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
    private final VersionedRules<AppModelAuthEntity> modelAuthCache = new VersionedRules<>("mg:auth:revision:");
    private final VersionedRules<AccessRuleEntity> personalAccessRules = new VersionedRules<>("mg:personal-access:revision:");

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

    // ===== 通用服务（/svc/** 流量）三个缓存 =====
    // TTL 与模型侧同名缓存逐一对齐：服务配置/凭证 30s、授权 60s。
    // 对齐不是巧合——两条链路的变更频率与"配置改完多久生效"的心理预期是一样的，
    // 取不同值只会让运营记两套规则。写操作一律 evict，TTL 只是兜底。

    /** 服务配置缓存：key = serviceId，TTL 30s。空结果（Optional.empty）同样缓存，防无效编码刷库 */
    private final Cache<String, Optional<ServiceEntity>> serviceCache = Caffeine.newBuilder()
            .expireAfterWrite(30, TimeUnit.SECONDS)
            .maximumSize(1_000)
            .build();

    /** 服务上游凭证缓存（含已解密 SK 与 extraHeaders）：key = serviceId，TTL 30s */
    private final Cache<String, Optional<ServiceCredentialEntity>> serviceCredentialCache = Caffeine.newBuilder()
            .expireAfterWrite(30, TimeUnit.SECONDS)
            .maximumSize(1_000)
            .build();

    /** 服务授权缓存：key = appId，TTL 60s，与 modelAuthCache 对齐 */
    private final Cache<String, List<AppServiceAuthEntity>> serviceAuthCache = Caffeine.newBuilder()
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
     * 按 appId 获取当前授权，版本改变或快照过期时从数据库重新加载。
     */
    public List<AppModelAuthEntity> getModelAuths(String appId, Supplier<List<AppModelAuthEntity>> dbLoader) {
        return modelAuthCache.get(appId, dbLoader);
    }

    /** 授权增删改后失效本地快照，并在提交后写入其他实例可观察的版本。 */
    public void evictModelAuthCache(String appId) {
        modelAuthCache.invalidateAfterCommit(appId);
    }

    private record RuleSnapshot<T>(List<T> rules, String revision) {
        boolean isCurrent(Optional<String> observedRevision) {
            return observedRevision.isEmpty() || Objects.equals(revision, observedRevision.get());
        }
    }

    private final class VersionedRules<T> {
        private final String keyPrefix;
        private final Cache<String, RuleSnapshot<T>> snapshots = Caffeine.newBuilder()
                .expireAfterWrite(RULE_TTL_SECONDS, TimeUnit.SECONDS)
                .maximumSize(5_000)
                .build();
        private final Cache<String, Optional<String>> revisions = Caffeine.newBuilder()
                .expireAfterWrite(1, TimeUnit.SECONDS)
                .maximumSize(5_000)
                .build();

        private VersionedRules(String keyPrefix) {
            this.keyPrefix = keyPrefix;
        }

        private List<T> get(String appId, Supplier<List<T>> dbLoader) {
            Optional<String> revision = revisions.get(appId, this::readRevision);
            RuleSnapshot<T> snapshot = snapshots.getIfPresent(appId);
            if (snapshot != null && snapshot.isCurrent(revision)) return snapshot.rules();
            return snapshots.asMap().compute(appId, (id, cached) -> {
                if (cached != null && cached.isCurrent(revision)) return cached;
                return new RuleSnapshot<>(dbLoader.get(), revision.orElse(null));
            }).rules();
        }

        private Optional<String> readRevision(String appId) {
            try {
                return Optional.of(Objects.toString(cacheProvider.get(keyPrefix + appId), ""));
            } catch (Exception ex) {
                log.debug("[Cache] 读取共享规则版本失败: key={}: {}", keyPrefix + appId, ex.getMessage());
                return Optional.empty();
            }
        }

        private void invalidateAfterCommit(String appId) {
            if (TransactionSynchronizationManager.isActualTransactionActive()
                    && TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        advanceRevision(appId);
                    }
                });
            } else {
                advanceRevision(appId);
            }
        }

        private void advanceRevision(String appId) {
            try {
                cacheProvider.setex(keyPrefix + appId, RULE_REVISION_TTL_SECONDS, UUID.randomUUID().toString());
            } catch (Exception ex) {
                log.warn("[Cache] 发布共享规则版本失败，其他实例将按本地 TTL 更新: key={}: {}", keyPrefix + appId, ex.getMessage());
            } finally {
                revisions.invalidate(appId);
                snapshots.invalidate(appId);
            }
        }
    }

    // ===== 访问规则缓存（仅 L1，不存 Redis） =====

    /** 原子加载，理由同 {@link #getCredential}。 */
    public List<AccessRuleEntity> getAccessRules(String appId, boolean personal, Supplier<List<AccessRuleEntity>> dbLoader) {
        return personal ? personalAccessRules.get(appId, dbLoader) : accessRuleCache.get(appId, k -> dbLoader.get());
    }

    public void evictPersonalAccessRuleCache(String appId) {
        personalAccessRules.invalidateAfterCommit(appId);
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

    // ===== 通用服务缓存（仅 L1，不存 Redis） =====

    /**
     * 服务配置，原子加载（{@code Cache.get(key, mappingFunction)}）——理由同 {@link #getCredential}：
     * Caffeine 对同一 key 的并发 miss 只执行一次 loader，避免缓存过期瞬间并发打 DB。
     * 空结果本身也缓存，防止用不存在的服务编码反复刷库。
     */
    public Optional<ServiceEntity> getService(String serviceId, Supplier<Optional<ServiceEntity>> dbLoader) {
        return serviceCache.get(serviceId, k -> dbLoader.get());
    }

    public void evictServiceCache(String serviceId) {
        serviceCache.invalidate(serviceId);
    }

    /** 服务上游凭证（已解密），原子加载，理由同上。 */
    public Optional<ServiceCredentialEntity> getServiceCredential(
            String serviceId, Supplier<Optional<ServiceCredentialEntity>> dbLoader) {
        return serviceCredentialCache.get(serviceId, k -> dbLoader.get());
    }

    public void evictServiceCredentialCache(String serviceId) {
        serviceCredentialCache.invalidate(serviceId);
    }

    /** 某 App 的服务授权列表，原子加载，理由同上。 */
    public List<AppServiceAuthEntity> getServiceAuths(
            String appId, Supplier<List<AppServiceAuthEntity>> dbLoader) {
        return serviceAuthCache.get(appId, k -> dbLoader.get());
    }

    public void evictServiceAuthCache(String appId) {
        serviceAuthCache.invalidate(appId);
    }

    /**
     * 全清服务授权缓存。
     *
     * <p>删除服务时用：授权缓存按 appId 组织，删一个服务会影响所有授权过它的 app，
     * 要精确失效就得先查出这些 appId。删服务是低频管理操作，全清的代价只是
     * 各 app 下次请求多查一次 DB，比多一次查询+多一处可能漏掉的逻辑划算。
     */
    public void evictAllServiceAuthCache() {
        serviceAuthCache.invalidateAll();
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

    /**
     * 将凭证 JSON 写入 Redis（TTL=30 天）的预留辅助方法；当前业务没有调用，不能视为已启用 L2。
     */
    public void setRedisCredential(String secretHash, String json) {
        try {
            cacheProvider.setex("mg:cred:" + secretHash, CREDENTIAL_REDIS_TTL_SECONDS, json);
        } catch (Exception ex) {
            log.warn("[Cache] Redis set failed: {}", ex.getMessage());
        }
    }

    /** 从 Redis 读取凭证 JSON 的预留辅助方法；Redis 故障时返回 {@code Optional.empty()}。 */
    public Optional<String> getRedisCredential(String secretHash) {
        try {
            return Optional.ofNullable(cacheProvider.get("mg:cred:" + secretHash));
        } catch (Exception ex) {
            log.warn("[Cache] Redis get failed: {}", ex.getMessage());
            return Optional.empty();
        }
    }
}
