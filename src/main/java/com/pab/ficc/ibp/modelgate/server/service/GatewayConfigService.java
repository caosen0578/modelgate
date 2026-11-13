package com.pab.ficc.ibp.modelgate.server.service;

import com.pab.ficc.ibp.modelgate.server.common.BusinessException;
import com.pab.ficc.ibp.modelgate.server.common.ErrorCode;
import com.pab.ficc.ibp.modelgate.server.domain.entity.GatewayConfigEntity;
import com.pab.ficc.ibp.modelgate.server.mapper.GatewayConfigMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URISyntaxException;
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

    public Optional<String> findModelPublicBaseUrl() {
        // 示例打开时读取单条，跨实例保存/删除后无需等待定时刷新。
        return Optional.ofNullable(configMapper.selectById(GatewayConfigEntity.KEY_PUBLIC_GATEWAY_BASE_URL))
                .map(GatewayConfigEntity::getConfigValue)
                .map(GatewayConfigService::normalizeModelPublicBaseUrl);
    }

    public GatewayConfigEntity upsert(GatewayConfigEntity config, String operatorName) {
        if (GatewayConfigEntity.KEY_PUBLIC_GATEWAY_BASE_URL.equals(config.getConfigKey())) {
            config.setConfigValue(normalizeModelPublicBaseUrl(config.getConfigValue()));
        }
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

    private static String normalizeModelPublicBaseUrl(String raw) {
        String value = raw.strip();
        URI uri;
        try {
            uri = new URI(value);
        } catch (URISyntaxException e) {
            throw invalidModelPublicBaseUrl();
        }
        String scheme = uri.getScheme();
        String path = uri.getPath();
        if (uri.toASCIIString().length() > 4000 || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                || uri.getHost() == null || uri.getPort() == 0 || uri.getPort() > 65535
                || uri.getRawAuthority().endsWith(":")
                || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                || path.indexOf('\\') >= 0 || path.matches("(?s).*/\\.{1,2}(/.*)?")
                || path.chars().anyMatch(Character::isISOControl)) {
            throw invalidModelPublicBaseUrl();
        }
        // 基础地址包含外部文根。具体 /v1 接口路径由接入示例拼接。
        if (path.matches(".*/v1(?:/(?:chat/completions|completions|responses|messages|embeddings|images/generations|audio/speech|models))?/*")) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "请填写模型对外基础地址，去掉末尾的 /v1 或具体接口路径");
        }
        return uri.toASCIIString().replaceAll("/+$", "");
    }

    private static BusinessException invalidModelPublicBaseUrl() {
        return new BusinessException(ErrorCode.INVALID_PARAMETER,
                "请填写完整的 HTTP(S) 地址，例如 https://api.example.com/modelgate，地址中不能包含账号密码、查询参数或片段");
    }
}
