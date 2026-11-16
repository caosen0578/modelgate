package com.pab.ficc.ibp.modelgate.server.cache;

import com.pab.ficc.ibp.modelgate.server.domain.entity.AppModelAuthEntity;
import com.pab.framework.redis.CacheProvider;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ModelAuthCacheRevisionTest {
    private static final String APP_ID = "test-app";
    private static final String VERSION_KEY = "mg:auth:revision:" + APP_ID;

    @Test
    void anotherInstanceObservesAnUpdateBeforeAuthorizationCacheExpires() throws Exception {
        CacheProvider redis = mock(CacheProvider.class);
        var version = new AtomicReference<String>();
        when(redis.get(VERSION_KEY)).thenAnswer(invocation -> version.get());
        when(redis.setex(eq(VERSION_KEY), anyInt(), anyString())).thenAnswer(invocation -> {
            version.set(invocation.getArgument(2));
            return "OK";
        });
        var writer = new GatewayCacheManager(redis);
        var reader = new GatewayCacheManager(redis);
        var quota = new AtomicInteger(100);
        var loads = new AtomicInteger();
        Supplier<List<AppModelAuthEntity>> load = () -> {
            loads.incrementAndGet();
            var auth = new AppModelAuthEntity();
            auth.setMonthlyTokens((long) quota.get());
            return List.of(auth);
        };

        assertEquals(100L, limit(reader, load));
        assertEquals(100L, limit(writer, load));
        quota.set(200);
        writer.evictModelAuthCache(APP_ID);
        assertEquals(200L, limit(writer, load));
        assertTimeoutPreemptively(Duration.ofSeconds(4), () -> {
            while (limit(reader, load) != 200L) Thread.sleep(50);
        });
        assertEquals(4, loads.get(), "每个实例只需加载旧授权和修改后的授权");
        verify(redis, never()).del(anyString());
    }

    @Test
    void publishesAfterCommitAndInvalidatesLocallyEvenWhenRedisWriteFails() {
        CacheProvider redis = mock(CacheProvider.class);
        when(redis.setex(eq(VERSION_KEY), anyInt(), anyString())).thenThrow(new IllegalStateException("offline"));
        var manager = new GatewayCacheManager(redis);
        var quota = new AtomicInteger(100);
        Supplier<List<AppModelAuthEntity>> load = () -> {
            var auth = new AppModelAuthEntity();
            auth.setMonthlyTokens((long) quota.get());
            return List.of(auth);
        };
        assertEquals(100L, limit(manager, load));

        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
        try {
            manager.evictModelAuthCache(APP_ID);
            verify(redis, never()).setex(eq(VERSION_KEY), anyInt(), anyString());
            quota.set(200);
            assertEquals(100L, limit(manager, load));
            TransactionSynchronizationManager.getSynchronizations().forEach(sync -> sync.afterCommit());
            assertEquals(200L, limit(manager, load));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    private static long limit(GatewayCacheManager cache, Supplier<List<AppModelAuthEntity>> load) {
        return cache.getModelAuths(APP_ID, load).get(0).getMonthlyTokens();
    }
}
