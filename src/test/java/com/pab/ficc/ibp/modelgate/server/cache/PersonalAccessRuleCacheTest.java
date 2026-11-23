package com.pab.ficc.ibp.modelgate.server.cache;

import com.pab.ficc.ibp.modelgate.server.admin.IpMatcher;
import com.pab.ficc.ibp.modelgate.server.admin.PersonalSourcePolicy;
import com.pab.ficc.ibp.modelgate.server.domain.entity.AccessRuleEntity;
import com.pab.framework.redis.CacheProvider;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PersonalAccessRuleCacheTest {
    @Test
    void addingAndRemovingTheFirstIpRuleUpdatesAnotherInstance() {
        String appId = "P_cache_test";
        String key = "mg:personal-access:revision:" + appId;
        CacheProvider redis = mock(CacheProvider.class);
        var version = new AtomicReference<String>();
        when(redis.get(key)).thenAnswer(invocation -> version.get());
        when(redis.setex(eq(key), anyInt(), anyString())).thenAnswer(invocation -> {
            version.set(invocation.getArgument(2));
            return "OK";
        });
        var writer = new GatewayCacheManager(redis);
        var reader = new GatewayCacheManager(redis);
        var savedRules = new AtomicReference<List<AccessRuleEntity>>(List.of());
        assertTrue(reader.getAccessRules(appId, true, savedRules::get).isEmpty());

        var rule = new AccessRuleEntity();
        rule.setRuleType("ip");
        rule.setRuleValue("192.0.2.10");
        savedRules.set(List.of(rule));
        writer.evictPersonalAccessRuleCache(appId);

        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            List<AccessRuleEntity> rules = reader.getAccessRules(appId, true, savedRules::get);
            while (rules.isEmpty()) {
                Thread.sleep(50);
                rules = reader.getAccessRules(appId, true, savedRules::get);
            }
            var policy = new PersonalSourcePolicy(new IpMatcher(), "");
            assertTrue(policy.allows("192.0.2.10", null, rules));
            assertFalse(policy.allows("192.0.2.11", null, rules));
        });

        savedRules.set(List.of());
        writer.evictPersonalAccessRuleCache(appId);
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            while (!reader.getAccessRules(appId, true, savedRules::get).isEmpty()) Thread.sleep(50);
        });
    }
}
