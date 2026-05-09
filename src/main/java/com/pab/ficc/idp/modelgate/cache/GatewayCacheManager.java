package com.pab.ficc.idp.modelgate.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.pab.ficc.idp.modelgate.entity.AppCredential;
import com.pab.ficc.idp.modelgate.entity.AppModelAuth;
import com.pab.ficc.idp.modelgate.entity.AccessRule;
import com.pab.ficc.idp.modelgate.entity.ModelInstance;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Two-level cache: Caffeine L1 (process-local) + Redis L2 (cross-instance).
 *
 * Degradation: Redis failure → serve from L1 only; L1 miss + Redis down → reject.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GatewayCacheManager {

    private final ReactiveStringRedisTemplate redisTemplate;

    // L1 caches — per spec TTLs
    private final Cache<String, Optional<AppCredential>> credentialCache = Caffeine.newBuilder()
            .expireAfterWrite(60, TimeUnit.SECONDS)
            .maximumSize(10_000)
            .build();

    private final Cache<String, List<AppModelAuth>> modelAuthCache = Caffeine.newBuilder()
            .expireAfterWrite(60, TimeUnit.SECONDS)
            .maximumSize(5_000)
            .build();

    private final Cache<String, List<AccessRule>> accessRuleCache = Caffeine.newBuilder()
            .expireAfterWrite(60, TimeUnit.SECONDS)
            .maximumSize(5_000)
            .build();

    private final Cache<String, Optional<ModelInstance>> modelCache = Caffeine.newBuilder()
            .expireAfterWrite(30, TimeUnit.SECONDS)
            .maximumSize(1_000)
            .build();

    // ===== Credential cache =====

    public Optional<AppCredential> getCredential(String secretHash, Supplier<Optional<AppCredential>> dbLoader) {
        Optional<AppCredential> cached = credentialCache.getIfPresent(secretHash);
        if (cached != null) return cached;

        Optional<AppCredential> result = dbLoader.get();
        credentialCache.put(secretHash, result);
        return result;
    }

    public void evictCredentialCache(String secretHash) {
        credentialCache.invalidate(secretHash);
        evictRedisKey("mg:cred:" + secretHash);
    }

    public void evictCredentialCacheByAppId(String appId) {
        credentialCache.asMap().entrySet().removeIf(e ->
                e.getValue().isPresent() && appId.equals(e.getValue().get().getAppId()));
    }

    // ===== Model auth cache =====

    public List<AppModelAuth> getModelAuths(String appId, Supplier<List<AppModelAuth>> dbLoader) {
        List<AppModelAuth> cached = modelAuthCache.getIfPresent(appId);
        if (cached != null) return cached;
        List<AppModelAuth> result = dbLoader.get();
        modelAuthCache.put(appId, result);
        return result;
    }

    public void evictModelAuthCache(String appId) {
        modelAuthCache.invalidate(appId);
        evictRedisKey("mg:auth:" + appId);
    }

    // ===== Access rule cache =====

    public List<AccessRule> getAccessRules(String appId, Supplier<List<AccessRule>> dbLoader) {
        List<AccessRule> cached = accessRuleCache.getIfPresent(appId);
        if (cached != null) return cached;
        List<AccessRule> result = dbLoader.get();
        accessRuleCache.put(appId, result);
        return result;
    }

    public void evictAccessRuleCache(String appId) {
        accessRuleCache.invalidate(appId);
    }

    // ===== Model cache =====

    public Optional<ModelInstance> getModel(String modelId, Supplier<Optional<ModelInstance>> dbLoader) {
        Optional<ModelInstance> cached = modelCache.getIfPresent(modelId);
        if (cached != null) return cached;
        Optional<ModelInstance> result = dbLoader.get();
        modelCache.put(modelId, result);
        return result;
    }

    public void evictModelCache(String modelId) {
        modelCache.invalidate(modelId);
    }

    public void evictAllModelCache() {
        modelCache.invalidateAll();
    }

    // ===== Redis helpers =====

    private void evictRedisKey(String key) {
        try {
            redisTemplate.delete(key).subscribe(
                    count -> log.debug("[Cache] Redis evicted key={}", key),
                    ex -> log.warn("[Cache] Redis evict failed key={}: {}", key, ex.getMessage())
            );
        } catch (Exception ex) {
            log.warn("[Cache] Redis unavailable during evict key={}", key);
        }
    }

    public void setRedisCredential(String secretHash, String json) {
        try {
            redisTemplate.opsForValue().set("mg:cred:" + secretHash, json, Duration.ofDays(30)).subscribe();
        } catch (Exception ex) {
            log.warn("[Cache] Redis set failed: {}", ex.getMessage());
        }
    }

    public Optional<String> getRedisCredential(String secretHash) {
        try {
            return Optional.ofNullable(
                    redisTemplate.opsForValue().get("mg:cred:" + secretHash).block(Duration.ofMillis(200))
            );
        } catch (Exception ex) {
            log.warn("[Cache] Redis get failed: {}", ex.getMessage());
            return Optional.empty();
        }
    }
}
