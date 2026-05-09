package com.pab.ficc.idp.modelgate;

import com.pab.ficc.idp.modelgate.service.WhitelistService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("WhitelistService 单元测试")
class WhitelistServiceTest {

    @Mock
    private StringRedisTemplate redis;

    @Mock
    private ValueOperations<String, String> valueOps;

    private WhitelistService whitelistService;

    @BeforeEach
    void setUp() {
        whitelistService = new WhitelistService(redis);
    }

    @Test
    @DisplayName("null apiKey 应直接拒绝")
    void nullApiKey_shouldDeny() {
        assertThat(whitelistService.isAllowed(null)).isFalse();
        verifyNoInteractions(redis);
    }

    @Test
    @DisplayName("空字符串 apiKey 应直接拒绝")
    void blankApiKey_shouldDeny() {
        assertThat(whitelistService.isAllowed("  ")).isFalse();
        verifyNoInteractions(redis);
    }

    @Test
    @DisplayName("apiKey 在 Redis 白名单中 → 放行")
    void apiKeyInRedis_shouldAllow() {
        when(redis.hasKey("wl:apikey:valid-key")).thenReturn(true);
        assertThat(whitelistService.isAllowed("valid-key")).isTrue();
    }

    @Test
    @DisplayName("apiKey 不在 Redis 白名单 → 拒绝")
    void apiKeyNotInRedis_shouldDeny() {
        when(redis.hasKey("wl:apikey:unknown-key")).thenReturn(false);
        assertThat(whitelistService.isAllowed("unknown-key")).isFalse();
    }

    @Test
    @DisplayName("Redis 故障时 → 拒绝新 key")
    void redisDown_shouldDenyUnknownKey() {
        when(redis.hasKey(anyString())).thenThrow(new RuntimeException("Connection refused"));
        assertThat(whitelistService.isAllowed("some-key")).isFalse();
    }

    @Test
    @DisplayName("本地缓存命中时不再访问 Redis")
    void localCacheHit_shouldNotCallRedis() {
        // 第一次访问 → 走 Redis
        when(redis.hasKey("wl:apikey:cached-key")).thenReturn(true);
        whitelistService.isAllowed("cached-key");

        // 第二次访问 → 命中本地缓存
        whitelistService.isAllowed("cached-key");

        // Redis 只被调用一次
        verify(redis, times(1)).hasKey("wl:apikey:cached-key");
    }

    @Test
    @DisplayName("removeFromRedis 后本地缓存应失效")
    void removeFromRedis_shouldInvalidateLocalCache() {
        // 预热本地缓存
        when(redis.hasKey("wl:apikey:key-to-remove")).thenReturn(true);
        whitelistService.isAllowed("key-to-remove");

        // 删除
        whitelistService.removeFromRedis("key-to-remove");

        // 再次查询 → 本地缓存已失效，重新走 Redis
        when(redis.hasKey("wl:apikey:key-to-remove")).thenReturn(false);
        assertThat(whitelistService.isAllowed("key-to-remove")).isFalse();

        // Redis 共被调用 2 次（预热 1 次 + 失效后重查 1 次）
        verify(redis, times(2)).hasKey("wl:apikey:key-to-remove");
    }
}
