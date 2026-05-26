package com.pab.ficc.ibp.modelgate.server.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pab.ficc.ibp.modelgate.server.common.ErrorCode;
import com.pab.ficc.ibp.modelgate.server.domain.entity.AppModelAuth;
import com.pab.ficc.ibp.modelgate.server.service.ModelAuthService;
import com.pab.ficc.ibp.modelgate.server.service.RateLimitService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class QuotaFilterTest {

    @Mock RateLimitService rateLimitService;
    @Mock ModelAuthService modelAuthService;
    @Mock GatewayFilterChain chain;

    @InjectMocks QuotaFilter quotaFilter;

    @BeforeEach
    void setup() {
        // Inject real ObjectMapper so reject() can serialize error responses
        ReflectionTestUtils.setField(quotaFilter, "objectMapper", new ObjectMapper());
        when(chain.filter(any())).thenReturn(Mono.empty());
    }

    // -------------------------------------------------------------------------
    // skip conditions
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("filter — /admin/ path → skips quota check")
    void filter_adminPath_skips() {
        var exchange = exchange(HttpMethod.GET, "/admin/models", null, null);

        StepVerifier.create(quotaFilter.filter(exchange, chain)).verifyComplete();

        verify(chain).filter(any());
        verifyNoInteractions(rateLimitService, modelAuthService);
    }

    @Test
    @DisplayName("filter — no X-Mg-App header → skips quota check")
    void filter_noAppHeader_skips() {
        var exchange = exchange(HttpMethod.POST, "/v1/chat/completions", null, null);

        StepVerifier.create(quotaFilter.filter(exchange, chain)).verifyComplete();

        verify(chain).filter(any());
        verifyNoInteractions(rateLimitService, modelAuthService);
    }

    // -------------------------------------------------------------------------
    // fallback (modelId not yet resolved)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("filter — modelId null → uses global per-app rate limit")
    void filter_modelIdNull_usesGlobalRate() {
        when(rateLimitService.getDefaultRate()).thenReturn(10);
        when(rateLimitService.isAllowed(eq("app:app-1"), eq(10))).thenReturn(true);
        var exchange = exchange(HttpMethod.POST, "/v1/chat", "app-1", null);

        StepVerifier.create(quotaFilter.filter(exchange, chain)).verifyComplete();

        verify(chain).filter(any());
        verify(rateLimitService).isAllowed("app:app-1", 10);
    }

    @Test
    @DisplayName("filter — modelId null, global rate exceeded → 429")
    void filter_modelIdNull_globalRateExceeded_returns429() {
        when(rateLimitService.getDefaultRate()).thenReturn(10);
        when(rateLimitService.isAllowed(eq("app:app-1"), eq(10))).thenReturn(false);
        var exchange = exchange(HttpMethod.POST, "/v1/chat", "app-1", null);

        StepVerifier.create(quotaFilter.filter(exchange, chain)).verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        verify(chain, never()).filter(any());
    }

    // -------------------------------------------------------------------------
    // per-model quota checks
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("filter — QPS limit exceeded → 429")
    void filter_qpsExceeded_returns429() {
        setupAuth("app-1", "gpt-4", 5, 0, 0L);
        when(rateLimitService.isQpsAllowed("app-1", "gpt-4", 5)).thenReturn(false);
        var exchange = exchange(HttpMethod.POST, "/v1/chat", "app-1", "gpt-4");

        StepVerifier.create(quotaFilter.filter(exchange, chain)).verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        verify(chain, never()).filter(any());
    }

    @Test
    @DisplayName("filter — daily limit exceeded → 429")
    void filter_dailyExceeded_returns429() {
        setupAuth("app-1", "gpt-4", 0, 1000, 0L);
        when(rateLimitService.isQpsAllowed(any(), any(), anyInt())).thenReturn(true);
        when(rateLimitService.isDailyAllowed("app-1", "gpt-4", 1000)).thenReturn(false);
        var exchange = exchange(HttpMethod.POST, "/v1/chat", "app-1", "gpt-4");

        StepVerifier.create(quotaFilter.filter(exchange, chain)).verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    @DisplayName("filter — monthly token quota exceeded → 429")
    void filter_monthlyTokensExceeded_returns429() {
        setupAuth("app-1", "gpt-4", 0, 0, 500_000L);
        when(rateLimitService.isQpsAllowed(any(), any(), anyInt())).thenReturn(true);
        when(rateLimitService.isDailyAllowed(any(), any(), anyInt())).thenReturn(true);
        when(rateLimitService.checkMonthlyTokens("app-1", "gpt-4", 500_000L)).thenReturn(false);
        var exchange = exchange(HttpMethod.POST, "/v1/chat", "app-1", "gpt-4");

        StepVerifier.create(quotaFilter.filter(exchange, chain)).verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    @DisplayName("filter — all quotas within limits → passes to chain")
    void filter_allAllowed_passesToChain() {
        setupAuth("app-1", "gpt-4", 10, 1000, 500_000L);
        when(rateLimitService.isQpsAllowed(any(), any(), anyInt())).thenReturn(true);
        when(rateLimitService.isDailyAllowed(any(), any(), anyInt())).thenReturn(true);
        when(rateLimitService.checkMonthlyTokens(any(), any(), anyLong())).thenReturn(true);
        var exchange = exchange(HttpMethod.POST, "/v1/chat", "app-1", "gpt-4");

        StepVerifier.create(quotaFilter.filter(exchange, chain)).verifyComplete();

        verify(chain).filter(any());
        assertThat(exchange.getResponse().getStatusCode()).isNull();
    }

    @Test
    @DisplayName("filter — auth not found for model → passes (auth enforcement is ModelAuthFilter's job)")
    void filter_authNotFound_passes() {
        when(modelAuthService.listByAppIdCached("app-1")).thenReturn(List.of());
        var exchange = exchange(HttpMethod.POST, "/v1/chat", "app-1", "gpt-4");

        StepVerifier.create(quotaFilter.filter(exchange, chain)).verifyComplete();

        verify(chain).filter(any());
    }

    @Test
    @DisplayName("filter — order is -160")
    void filter_orderIs160() {
        assertThat(quotaFilter.getOrder()).isEqualTo(-160);
    }

    // -------------------------------------------------------------------------
    // helpers
    // -------------------------------------------------------------------------

    private MockServerWebExchange exchange(HttpMethod method, String path, String appId, String modelId) {
        MockServerHttpRequest.BaseBuilder<?> builder = MockServerHttpRequest.method(method, path);
        if (appId != null) builder.header("X-Mg-App", appId);
        MockServerWebExchange exchange = MockServerWebExchange.from(
                ((MockServerHttpRequest.BaseBuilder<?>) builder).build());
        if (modelId != null) exchange.getAttributes().put("mg.modelId", modelId);
        return exchange;
    }

    private void setupAuth(String appId, String modelId, int qpsLimit, int dailyLimit, long monthlyTokens) {
        AppModelAuth auth = new AppModelAuth();
        auth.setAppId(appId);
        auth.setModelId(modelId);
        auth.setQpsLimit(qpsLimit);
        auth.setDailyLimit(dailyLimit);
        auth.setMonthlyTokens(monthlyTokens);
        when(modelAuthService.listByAppIdCached(appId)).thenReturn(List.of(auth));
    }
}
