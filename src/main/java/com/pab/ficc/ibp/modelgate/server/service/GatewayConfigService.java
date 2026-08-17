package com.pab.ficc.ibp.modelgate.server.service;

import com.pab.ficc.ibp.modelgate.server.domain.entity.GatewayConfigEntity;
import com.pab.ficc.ibp.modelgate.server.mapper.GatewayConfigMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class GatewayConfigService {

    private final GatewayConfigMapper configMapper;

    private final ConcurrentHashMap<String, String> configCache = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        loadFromDb();
        log.info("[GatewayConfig] 初始化完成, 配置项数={}", configCache.size());
    }

    @Scheduled(fixedDelay = 30_000, initialDelay = 30_000)
    public void refresh() {
        try {
            loadFromDb();
            log.debug("[GatewayConfig] Refreshed from DB, entries={}", configCache.size());
        } catch (Exception e) {
            log.error("[GatewayConfig] Refresh failed, using cached values: {}", e.getMessage());
        }
    }

    /**
     * 用 DB 全量快照**替换**缓存。
     *
     * <p>此前实现只做 {@code put}、从不移除，导致被删除的 key 永远留在缓存里：
     * 直接改库删除 → 定时刷新不会清掉；多实例部署时 A 实例删的 key，B 实例照旧生效。
     * 现改为先算出完整快照再做 {@code keySet().retainAll} + {@code putAll}，
     * 使删除能够随定时刷新收敛到所有实例。
     *
     * <p>不直接 {@code clear()} 再 {@code putAll()}：那样会出现一个短暂的空窗口，
     * 并发读会读到缺省值，造成开关瞬间抖动。
     */
    private void loadFromDb() {
        Map<String, String> snapshot = configMapper.selectList(null).stream()
                .collect(Collectors.toMap(GatewayConfigEntity::getConfigKey,
                        GatewayConfigEntity::getConfigValue, (a, b) -> b));
        configCache.putAll(snapshot);
        configCache.keySet().retainAll(snapshot.keySet());
    }

    public boolean isCircuitBreakerEnabled() {
        return Boolean.parseBoolean(configCache.getOrDefault(GatewayConfigEntity.KEY_CIRCUIT_BREAKER_ENABLED, "true"));
    }

    public Optional<String> get(String key) {
        return Optional.ofNullable(configCache.get(key));
    }

    public Map<String, String> snapshot() {
        return Map.copyOf(configCache);
    }

    public List<GatewayConfigEntity> findAll() {
        return configMapper.selectList(null);
    }

    public GatewayConfigEntity upsert(GatewayConfigEntity config, String operatorName) {
        config.setUpdater(operatorName);
        config.setUpdateTime(LocalDateTime.now());

        GatewayConfigEntity existing = configMapper.selectById(config.getConfigKey());
        if (existing != null) {
            configMapper.updateById(config);
        } else {
            config.setCreateTime(LocalDateTime.now());
            configMapper.insert(config);
        }

        configCache.put(config.getConfigKey(), config.getConfigValue());
        log.info("[GatewayConfig] Updated: key={}, by={}", config.getConfigKey(), operatorName);
        return configMapper.selectById(config.getConfigKey());
    }

    public void delete(String key) {
        configMapper.deleteById(key);
        configCache.remove(key);
        log.info("[GatewayConfig] Deleted: key={}", key);
    }
}
