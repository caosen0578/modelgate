package com.pab.ficc.ibp.modelgate.server.service;

import com.pab.ficc.ibp.modelgate.server.domain.entity.GatewayConfig;
import com.pab.ficc.ibp.modelgate.server.mapper.GatewayConfigMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GatewayConfigServiceTest {

    @Mock GatewayConfigMapper configMapper;

    @InjectMocks GatewayConfigService gatewayConfigService;

    @BeforeEach
    void setup() {
        ReflectionTestUtils.setField(gatewayConfigService, "fallbackUpstreamUrl", "http://127.0.0.1:9090");
    }

    // -------------------------------------------------------------------------
    // init / loadFromDb
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("init — loads entries from DB into in-memory cache")
    void init_loadsFromDb() {
        when(configMapper.selectList(null))
                .thenReturn(List.of(config(GatewayConfig.KEY_UPSTREAM_URL, "http://upstream:8080")));

        gatewayConfigService.init();

        assertThat(gatewayConfigService.getUpstreamUrl()).isEqualTo("http://upstream:8080");
    }

    @Test
    @DisplayName("init — empty DB → falls back to @Value default")
    void init_emptyDb_fallsBackToDefault() {
        when(configMapper.selectList(null)).thenReturn(Collections.emptyList());

        gatewayConfigService.init();

        assertThat(gatewayConfigService.getUpstreamUrl()).isEqualTo("http://127.0.0.1:9090");
    }

    // -------------------------------------------------------------------------
    // isAuthEnabled
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("isAuthEnabled — returns true when DB has 'true'")
    void isAuthEnabled_true() {
        when(configMapper.selectList(null))
                .thenReturn(List.of(config(GatewayConfig.KEY_AUTH_ENABLED, "true")));
        gatewayConfigService.init();

        assertThat(gatewayConfigService.isAuthEnabled()).isTrue();
    }

    @Test
    @DisplayName("isAuthEnabled — returns false when DB has 'false'")
    void isAuthEnabled_false() {
        when(configMapper.selectList(null))
                .thenReturn(List.of(config(GatewayConfig.KEY_AUTH_ENABLED, "false")));
        gatewayConfigService.init();

        assertThat(gatewayConfigService.isAuthEnabled()).isFalse();
    }

    @Test
    @DisplayName("isAuthEnabled — defaults to true when key not present")
    void isAuthEnabled_defaultsTrue() {
        when(configMapper.selectList(null)).thenReturn(Collections.emptyList());
        gatewayConfigService.init();

        assertThat(gatewayConfigService.isAuthEnabled()).isTrue();
    }

    // -------------------------------------------------------------------------
    // isCircuitBreakerEnabled
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("isCircuitBreakerEnabled — defaults to true when key absent")
    void isCircuitBreakerEnabled_defaultsTrue() {
        when(configMapper.selectList(null)).thenReturn(Collections.emptyList());
        gatewayConfigService.init();

        assertThat(gatewayConfigService.isCircuitBreakerEnabled()).isTrue();
    }

    // -------------------------------------------------------------------------
    // getDefaultRateLimit
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("getDefaultRateLimit — returns parsed integer from DB")
    void getDefaultRateLimit_returnsParsedValue() {
        when(configMapper.selectList(null))
                .thenReturn(List.of(config(GatewayConfig.KEY_DEFAULT_RATE_LIMIT, "20")));
        gatewayConfigService.init();

        assertThat(gatewayConfigService.getDefaultRateLimit()).isEqualTo(20);
    }

    @Test
    @DisplayName("getDefaultRateLimit — returns 10 when key absent")
    void getDefaultRateLimit_defaultsTen() {
        when(configMapper.selectList(null)).thenReturn(Collections.emptyList());
        gatewayConfigService.init();

        assertThat(gatewayConfigService.getDefaultRateLimit()).isEqualTo(10);
    }

    @Test
    @DisplayName("getDefaultRateLimit — returns 10 when value is non-numeric")
    void getDefaultRateLimit_nonNumeric_returns10() {
        when(configMapper.selectList(null))
                .thenReturn(List.of(config(GatewayConfig.KEY_DEFAULT_RATE_LIMIT, "not-a-number")));
        gatewayConfigService.init();

        assertThat(gatewayConfigService.getDefaultRateLimit()).isEqualTo(10);
    }

    // -------------------------------------------------------------------------
    // get
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("get — known key → returns Optional with value")
    void get_knownKey() {
        when(configMapper.selectList(null))
                .thenReturn(List.of(config("custom.key", "custom-value")));
        gatewayConfigService.init();

        assertThat(gatewayConfigService.get("custom.key")).contains("custom-value");
    }

    @Test
    @DisplayName("get — unknown key → returns empty Optional")
    void get_unknownKey() {
        when(configMapper.selectList(null)).thenReturn(Collections.emptyList());
        gatewayConfigService.init();

        assertThat(gatewayConfigService.get("no.such.key")).isEmpty();
    }

    // -------------------------------------------------------------------------
    // snapshot
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("snapshot — returns immutable copy of in-memory cache")
    void snapshot_returnsImmutableCopy() {
        when(configMapper.selectList(null))
                .thenReturn(List.of(config("k1", "v1"), config("k2", "v2")));
        gatewayConfigService.init();

        Map<String, String> snap = gatewayConfigService.snapshot();

        assertThat(snap).containsEntry("k1", "v1").containsEntry("k2", "v2");
        assertThatThrownBy(() -> snap.put("x", "y"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    // -------------------------------------------------------------------------
    // upsert — insert
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("upsert — new key → inserts and updates in-memory cache")
    void upsert_insert_newKey() {
        when(configMapper.selectList(null)).thenReturn(Collections.emptyList());
        gatewayConfigService.init();

        GatewayConfig cfg = config("new.key", "new-value");
        when(configMapper.selectById("new.key")).thenReturn(null).thenReturn(cfg);

        gatewayConfigService.upsert(cfg, "admin");

        verify(configMapper).insert((GatewayConfig) cfg);
        verify(configMapper, never()).updateById((GatewayConfig) any());
        assertThat(gatewayConfigService.get("new.key")).contains("new-value");
    }

    // -------------------------------------------------------------------------
    // upsert — update
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("upsert — existing key → updates and refreshes in-memory cache")
    void upsert_update_existingKey() {
        GatewayConfig existing = config("existing.key", "old-value");
        when(configMapper.selectList(null)).thenReturn(List.of(existing));
        gatewayConfigService.init();

        GatewayConfig update = config("existing.key", "new-value");
        when(configMapper.selectById("existing.key")).thenReturn(existing).thenReturn(update);

        gatewayConfigService.upsert(update, "admin");

        verify(configMapper).updateById((GatewayConfig) update);
        verify(configMapper, never()).insert((GatewayConfig) any());
        assertThat(gatewayConfigService.get("existing.key")).contains("new-value");
    }

    // -------------------------------------------------------------------------
    // delete
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("delete — removes key from DB and in-memory cache")
    void delete_removesFromBothStores() {
        when(configMapper.selectList(null))
                .thenReturn(List.of(config("to.delete", "val")));
        gatewayConfigService.init();
        assertThat(gatewayConfigService.get("to.delete")).isPresent();

        gatewayConfigService.delete("to.delete");

        verify(configMapper).deleteById("to.delete");
        assertThat(gatewayConfigService.get("to.delete")).isEmpty();
    }

    // -------------------------------------------------------------------------
    // refresh
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("refresh — reloads config from DB without error")
    void refresh_reloadsSuccessfully() {
        when(configMapper.selectList(null))
                .thenReturn(Collections.emptyList())
                .thenReturn(List.of(config("k1", "v1")));

        gatewayConfigService.init();
        gatewayConfigService.refresh();

        assertThat(gatewayConfigService.get("k1")).contains("v1");
    }

    @Test
    @DisplayName("refresh — DB exception → keeps previous in-memory values")
    void refresh_dbException_keepsPreviousValues() {
        when(configMapper.selectList(null))
                .thenReturn(List.of(config("k1", "stable")))
                .thenThrow(new RuntimeException("DB is down"));

        gatewayConfigService.init();
        gatewayConfigService.refresh();  // should not throw

        assertThat(gatewayConfigService.get("k1")).contains("stable");
    }

    // -------------------------------------------------------------------------
    // helpers
    // -------------------------------------------------------------------------

    private GatewayConfig config(String key, String value) {
        GatewayConfig cfg = new GatewayConfig();
        cfg.setConfigKey(key);
        cfg.setConfigValue(value);
        return cfg;
    }
}
