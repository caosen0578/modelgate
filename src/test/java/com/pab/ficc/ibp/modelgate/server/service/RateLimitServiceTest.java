package com.pab.ficc.ibp.modelgate.server.service;

import com.pab.framework.redis.CacheProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RateLimitServiceTest {

    @Mock CacheProvider cacheProvider;

    @InjectMocks RateLimitService rateLimitService;

    @BeforeEach
    void setup() {
        ReflectionTestUtils.setField(rateLimitService, "defaultRate", 10);
    }

    // -------------------------------------------------------------------------
    // isAllowed
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("isAllowed — rateLimit=0 → always allows without Redis call")
    void isAllowed_zeroLimit_bypassesRedis() {
        assertThat(rateLimitService.isAllowed("api-key-1", 0)).isTrue();
        verifyNoInteractions(cacheProvider);
    }

    @Test
    @DisplayName("isAllowed — eval returns 1L → allowed")
    void isAllowed_evalReturns1_allowed() {
        when(cacheProvider.eval(anyString(), anyList(), anyList())).thenReturn(1L);
        assertThat(rateLimitService.isAllowed("api-key-1", 10)).isTrue();
    }

    @Test
    @DisplayName("isAllowed — eval returns 0L → throttled")
    void isAllowed_evalReturns0_throttled() {
        when(cacheProvider.eval(anyString(), anyList(), anyList())).thenReturn(0L);
        assertThat(rateLimitService.isAllowed("api-key-1", 10)).isFalse();
    }

    @Test
    @DisplayName("isAllowed — Redis throws → fail-open")
    void isAllowed_redisThrows_failOpen() {
        when(cacheProvider.eval(anyString(), anyList(), anyList()))
                .thenThrow(new RuntimeException("Redis down"));
        assertThat(rateLimitService.isAllowed("api-key-1", 10)).isTrue();
    }

    @Test
    @DisplayName("isAllowed — key contains 'rl:' prefix and rate as ARGV[1]")
    void isAllowed_keyFormat() {
        when(cacheProvider.eval(anyString(), anyList(), anyList())).thenReturn(1L);
        rateLimitService.isAllowed("my-key", 5);
        verify(cacheProvider).eval(anyString(),
                argThat(keys -> keys.get(0).startsWith("rl:my-key:")),
                eq(List.of("5", "2")));
    }

    // -------------------------------------------------------------------------
    // isQpsAllowed
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("isQpsAllowed — limit=0 → bypass without Redis")
    void isQpsAllowed_zeroLimit_bypass() {
        assertThat(rateLimitService.isQpsAllowed("app-1", "m1", 0)).isTrue();
        verifyNoInteractions(cacheProvider);
    }

    @Test
    @DisplayName("isQpsAllowed — key contains 'quota:qps:' prefix")
    void isQpsAllowed_keyFormat() {
        when(cacheProvider.eval(anyString(), anyList(), anyList())).thenReturn(1L);
        rateLimitService.isQpsAllowed("app-1", "m1", 5);
        verify(cacheProvider).eval(anyString(),
                argThat(keys -> keys.get(0).startsWith("rl:quota:qps:app-1:m1:")), anyList());
    }

    @Test
    @DisplayName("isQpsAllowed — throttled → returns false")
    void isQpsAllowed_throttled() {
        when(cacheProvider.eval(anyString(), anyList(), anyList())).thenReturn(0L);
        assertThat(rateLimitService.isQpsAllowed("app-1", "m1", 5)).isFalse();
    }

    // -------------------------------------------------------------------------
    // isDailyAllowed
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("isDailyAllowed — limit=0 → bypass without Redis")
    void isDailyAllowed_zeroLimit_bypass() {
        assertThat(rateLimitService.isDailyAllowed("app-1", "m1", 0)).isTrue();
        verifyNoInteractions(cacheProvider);
    }

    @Test
    @DisplayName("isDailyAllowed — eval returns 1L → allowed")
    void isDailyAllowed_allowed() {
        when(cacheProvider.eval(anyString(), anyList(), anyList())).thenReturn(1L);
        assertThat(rateLimitService.isDailyAllowed("app-1", "m1", 100)).isTrue();
    }

    @Test
    @DisplayName("isDailyAllowed — key contains 'quota:daily:' prefix with today's date")
    void isDailyAllowed_keyFormat() {
        when(cacheProvider.eval(anyString(), anyList(), anyList())).thenReturn(1L);
        rateLimitService.isDailyAllowed("app-1", "m1", 100);
        verify(cacheProvider).eval(anyString(),
                argThat(keys -> keys.get(0).startsWith("quota:daily:app-1:m1:")), anyList());
    }

    @Test
    @DisplayName("isDailyAllowed — Redis throws → fail-open")
    void isDailyAllowed_redisThrows_failOpen() {
        when(cacheProvider.eval(anyString(), anyList(), anyList()))
                .thenThrow(new RuntimeException("Redis down"));
        assertThat(rateLimitService.isDailyAllowed("app-1", "m1", 100)).isTrue();
    }

    // -------------------------------------------------------------------------
    // checkMonthlyTokens
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("checkMonthlyTokens — limit=0 → bypass without Redis")
    void checkMonthlyTokens_zeroLimit_bypass() {
        assertThat(rateLimitService.checkMonthlyTokens("app-1", "m1", 0L)).isTrue();
        verifyNoInteractions(cacheProvider);
    }

    @Test
    @DisplayName("checkMonthlyTokens — current < limit → allowed")
    void checkMonthlyTokens_underLimit_allowed() {
        when(cacheProvider.eval(anyString(), anyList(), anyList())).thenReturn(500L);
        assertThat(rateLimitService.checkMonthlyTokens("app-1", "m1", 1000L)).isTrue();
    }

    @Test
    @DisplayName("checkMonthlyTokens — current >= limit → denied")
    void checkMonthlyTokens_atLimit_denied() {
        when(cacheProvider.eval(anyString(), anyList(), anyList())).thenReturn(1000L);
        assertThat(rateLimitService.checkMonthlyTokens("app-1", "m1", 1000L)).isFalse();
    }

    @Test
    @DisplayName("checkMonthlyTokens — key not in Redis (returns 0) → allowed")
    void checkMonthlyTokens_keyMissing_allowed() {
        when(cacheProvider.eval(anyString(), anyList(), anyList())).thenReturn(0L);
        assertThat(rateLimitService.checkMonthlyTokens("app-1", "m1", 1000L)).isTrue();
    }

    @Test
    @DisplayName("checkMonthlyTokens — Redis throws → fail-open")
    void checkMonthlyTokens_redisThrows_failOpen() {
        when(cacheProvider.eval(anyString(), anyList(), anyList()))
                .thenThrow(new RuntimeException("Redis down"));
        assertThat(rateLimitService.checkMonthlyTokens("app-1", "m1", 1000L)).isTrue();
    }

    @Test
    @DisplayName("checkMonthlyTokens — key contains 'quota:monthly:' prefix")
    void checkMonthlyTokens_keyFormat() {
        when(cacheProvider.eval(anyString(), anyList(), anyList())).thenReturn(0L);
        rateLimitService.checkMonthlyTokens("app-1", "m1", 1000L);
        verify(cacheProvider).eval(anyString(),
                argThat(keys -> keys.get(0).startsWith("quota:monthly:app-1:m1:")),
                eq(List.of()));
    }

    // -------------------------------------------------------------------------
    // addMonthlyTokens
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("addMonthlyTokens — tokens=0 → skips Redis")
    void addMonthlyTokens_zeroTokens_skips() {
        rateLimitService.addMonthlyTokens("app-1", "m1", 0L);
        verifyNoInteractions(cacheProvider);
    }

    @Test
    @DisplayName("addMonthlyTokens — calls eval with tokens as ARGV[1]")
    void addMonthlyTokens_callsEvalWithTokens() {
        rateLimitService.addMonthlyTokens("app-1", "m1", 350L);
        verify(cacheProvider).eval(anyString(),
                argThat(keys -> keys.get(0).startsWith("quota:monthly:app-1:m1:")),
                argThat(args -> "350".equals(args.get(0))));
    }

    @Test
    @DisplayName("addMonthlyTokens — Redis throws → no exception propagated")
    void addMonthlyTokens_redisThrows_noException() {
        when(cacheProvider.eval(anyString(), anyList(), anyList()))
                .thenThrow(new RuntimeException("Redis down"));
        assertThatCode(() -> rateLimitService.addMonthlyTokens("app-1", "m1", 100L))
                .doesNotThrowAnyException();
    }

    // -------------------------------------------------------------------------
    // getDefaultRate
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("getDefaultRate — returns configured value")
    void getDefaultRate_returnsConfigured() {
        assertThat(rateLimitService.getDefaultRate()).isEqualTo(10);
    }
}
