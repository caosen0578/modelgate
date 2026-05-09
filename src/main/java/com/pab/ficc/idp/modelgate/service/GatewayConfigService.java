package com.pab.ficc.idp.modelgate.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.pab.ficc.idp.modelgate.entity.GatewayConfig;
import com.pab.ficc.idp.modelgate.mapper.GatewayConfigMapper;
import jakarta.annotation.PostConstruct;
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
        log.info("[GatewayConfig] Initialized, upstream.url={}", getUpstreamUrl());
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

    private void loadFromDb() {
        configMapper.selectList(null).forEach(cfg ->
                configCache.put(cfg.getConfigKey(), cfg.getConfigValue()));
    }

    public String getUpstreamUrl() {
        return configCache.getOrDefault(GatewayConfig.KEY_UPSTREAM_URL, fallbackUpstreamUrl);
    }

    public boolean isAuthEnabled() {
        return Boolean.parseBoolean(configCache.getOrDefault(GatewayConfig.KEY_AUTH_ENABLED, "true"));
    }

    public boolean isCircuitBreakerEnabled() {
        return Boolean.parseBoolean(configCache.getOrDefault(GatewayConfig.KEY_CIRCUIT_BREAKER_ENABLED, "true"));
    }

    public int getDefaultRateLimit() {
        try {
            return Integer.parseInt(configCache.getOrDefault(GatewayConfig.KEY_DEFAULT_RATE_LIMIT, "10"));
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

    public List<GatewayConfig> findAll() {
        return configMapper.selectList(null);
    }

    public GatewayConfig upsert(GatewayConfig config, String operatorName) {
        config.setUpdatedBy(operatorName);
        config.setUpdatedAt(LocalDateTime.now());

        GatewayConfig existing = configMapper.selectById(config.getConfigKey());
        if (existing != null) {
            configMapper.updateById(config);
        } else {
            config.setCreatedAt(LocalDateTime.now());
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
