package com.pab.ficc.ibp.modelgate.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.pab.ficc.ibp.modelgate.server.domain.entity.GatewayConfigEntity;
import com.pab.ficc.ibp.modelgate.server.mapper.GatewayConfigMapper;
import javax.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class GatewayConfigService {

    private final GatewayConfigMapper configMapper;

    private final ConcurrentHashMap<String, String> configCache = new ConcurrentHashMap<>();

    @Value("${modelgate.upstream.url:http://127.0.0.1:9090}")
    private String fallbackUpstreamUrl;

    @PostConstruct
    public void init() {
        loadFromDb();
        log.info("[GatewayConfigEntity] Initialized, upstream.url={}", getUpstreamUrl());
    }

    @Scheduled(fixedDelay = 30_000, initialDelay = 30_000)
    public void refresh() {
        try {
            loadFromDb();
            log.debug("[GatewayConfigEntity] Refreshed from DB, entries={}", configCache.size());
        } catch (Exception e) {
            log.error("[GatewayConfigEntity] Refresh failed, using cached values: {}", e.getMessage());
        }
    }

    private void loadFromDb() {
        configMapper.selectList(null).forEach(cfg ->
                configCache.put(cfg.getConfigKey(), cfg.getConfigValue()));
    }

    public String getUpstreamUrl() {
        return configCache.getOrDefault(GatewayConfigEntity.KEY_UPSTREAM_URL, fallbackUpstreamUrl);
    }

    public boolean isAuthEnabled() {
        return Boolean.parseBoolean(configCache.getOrDefault(GatewayConfigEntity.KEY_AUTH_ENABLED, "true"));
    }

    public boolean isCircuitBreakerEnabled() {
        return Boolean.parseBoolean(configCache.getOrDefault(GatewayConfigEntity.KEY_CIRCUIT_BREAKER_ENABLED, "true"));
    }

    public int getDefaultRateLimit() {
        try {
            return Integer.parseInt(configCache.getOrDefault(GatewayConfigEntity.KEY_DEFAULT_RATE_LIMIT, "10"));
        } catch (NumberFormatException e) {
            return 10;
        }
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
        config.setUpdatedBy(operatorName);
        config.setUpdateTime(LocalDateTime.now());

        GatewayConfigEntity existing = configMapper.selectById(config.getConfigKey());
        if (existing != null) {
            configMapper.updateById(config);
        } else {
            config.setCreateTime(LocalDateTime.now());
            configMapper.insert(config);
        }

        configCache.put(config.getConfigKey(), config.getConfigValue());
        log.info("[GatewayConfigEntity] Updated: key={}, by={}", config.getConfigKey(), operatorName);
        return configMapper.selectById(config.getConfigKey());
    }

    public void delete(String key) {
        configMapper.deleteById(key);
        configCache.remove(key);
        log.info("[GatewayConfigEntity] Deleted: key={}", key);
    }
}
