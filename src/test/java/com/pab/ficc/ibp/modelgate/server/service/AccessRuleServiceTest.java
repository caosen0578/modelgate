package com.pab.ficc.ibp.modelgate.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.pab.ficc.ibp.modelgate.server.cache.GatewayCacheManager;
import com.pab.ficc.ibp.modelgate.server.common.BusinessException;
import com.pab.ficc.ibp.modelgate.server.common.ErrorCode;
import com.pab.ficc.ibp.modelgate.server.domain.entity.AccessRule;
import com.pab.ficc.ibp.modelgate.server.mapper.AccessRuleMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.Serializable;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AccessRuleServiceTest {

    @Mock AccessRuleMapper accessRuleMapper;
    @Mock GatewayCacheManager cacheManager;

    @InjectMocks AccessRuleService accessRuleService;

    // -------------------------------------------------------------------------
    // listByAppId
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("listByAppId — delegates to mapper")
    void listByAppId_returnsList() {
        AccessRule r = rule(1L, "app-1");
        when(accessRuleMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(r));

        assertThat(accessRuleService.listByAppId("app-1")).containsExactly(r);
    }

    // -------------------------------------------------------------------------
    // listByAppIdCached
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("listByAppIdCached — delegates to cacheManager")
    void listByAppIdCached_delegatesToCache() {
        AccessRule r = rule(1L, "app-1");
        when(cacheManager.getAccessRules(eq("app-1"), any())).thenReturn(List.of(r));

        assertThat(accessRuleService.listByAppIdCached("app-1")).containsExactly(r);
    }

    // -------------------------------------------------------------------------
    // add
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("add — sets appId and createdAt, inserts, evicts cache")
    void add_insertsAndEvicts() {
        AccessRule r = new AccessRule();
        r.setRuleType("ip_whitelist");
        r.setRuleValue("10.0.0.1");

        AccessRule result = accessRuleService.add("app-1", r);

        verify(accessRuleMapper).insert((AccessRule) r);
        verify(cacheManager).evictAccessRuleCache("app-1");
        assertThat(result.getAppId()).isEqualTo("app-1");
        assertThat(result.getCreatedAt()).isNotNull();
    }

    // -------------------------------------------------------------------------
    // delete
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("delete — rule found and belongs to app → deletes and evicts cache")
    void delete_deletesAndEvicts() {
        AccessRule r = rule(10L, "app-1");
        when(accessRuleMapper.selectById(10L)).thenReturn(r);

        accessRuleService.delete("app-1", 10L);

        verify(accessRuleMapper).deleteById((Serializable) 10L);
        verify(cacheManager).evictAccessRuleCache("app-1");
    }

    @Test
    @DisplayName("delete — rule not found → throws RESOURCE_NOT_FOUND")
    void delete_notFound_throws() {
        when(accessRuleMapper.selectById(99L)).thenReturn(null);

        assertThatThrownBy(() -> accessRuleService.delete("app-1", 99L))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));

        verify(accessRuleMapper, never()).deleteById((Serializable) any());
    }

    @Test
    @DisplayName("delete — rule belongs to different app → throws RESOURCE_NOT_FOUND")
    void delete_wrongApp_throws() {
        AccessRule r = rule(10L, "other-app");
        when(accessRuleMapper.selectById(10L)).thenReturn(r);

        assertThatThrownBy(() -> accessRuleService.delete("app-1", 10L))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));

        verify(accessRuleMapper, never()).deleteById((Serializable) any());
    }

    // -------------------------------------------------------------------------
    // helpers
    // -------------------------------------------------------------------------

    private AccessRule rule(Long id, String appId) {
        AccessRule r = new AccessRule();
        r.setId(id);
        r.setAppId(appId);
        r.setRuleType("ip_whitelist");
        r.setRuleValue("10.0.0.1");
        return r;
    }
}
