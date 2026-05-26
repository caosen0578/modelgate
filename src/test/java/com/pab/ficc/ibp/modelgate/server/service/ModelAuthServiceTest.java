package com.pab.ficc.ibp.modelgate.server.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.pab.ficc.ibp.modelgate.server.cache.GatewayCacheManager;
import com.pab.ficc.ibp.modelgate.server.common.BusinessException;
import com.pab.ficc.ibp.modelgate.server.common.ErrorCode;
import com.pab.ficc.ibp.modelgate.server.domain.entity.AppModelAuth;
import com.pab.ficc.ibp.modelgate.server.mapper.AppModelAuthMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ModelAuthServiceTest {

    @Mock AppModelAuthMapper authMapper;
    @Mock GatewayCacheManager cacheManager;

    @InjectMocks ModelAuthService modelAuthService;

    @BeforeAll
    static void initMybatisPlus() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), AppModelAuth.class);
    }

    // -------------------------------------------------------------------------
    // listByAppId
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("listByAppId — delegates to mapper")
    void listByAppId_returnsList() {
        AppModelAuth a = auth("app-1", "gpt-4");
        when(authMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(a));

        assertThat(modelAuthService.listByAppId("app-1")).containsExactly(a);
    }

    // -------------------------------------------------------------------------
    // listByAppIdCached
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("listByAppIdCached — delegates to cacheManager")
    void listByAppIdCached_delegatesToCache() {
        AppModelAuth a = auth("app-1", "gpt-4");
        when(cacheManager.getModelAuths(eq("app-1"), any())).thenReturn(List.of(a));

        assertThat(modelAuthService.listByAppIdCached("app-1")).containsExactly(a);
    }

    // -------------------------------------------------------------------------
    // addAuthorizations
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("addAuthorizations — new modelIds → inserts each and evicts cache")
    void addAuthorizations_insertsNewEntries() {
        when(authMapper.exists(any(LambdaQueryWrapper.class))).thenReturn(false);

        AppModelAuth template = new AppModelAuth();
        template.setQpsLimit(10);
        template.setDailyLimit(1000);

        modelAuthService.addAuthorizations("app-1", List.of("m1", "m2"), template);

        verify(authMapper, times(2)).insert((AppModelAuth) any(AppModelAuth.class));
        verify(cacheManager).evictModelAuthCache("app-1");
    }

    @Test
    @DisplayName("addAuthorizations — already existing modelId → skips insert")
    void addAuthorizations_skipsExisting() {
        when(authMapper.exists(any(LambdaQueryWrapper.class)))
                .thenReturn(true)    // m1 already exists
                .thenReturn(false);  // m2 is new

        AppModelAuth template = new AppModelAuth();
        modelAuthService.addAuthorizations("app-1", List.of("m1", "m2"), template);

        verify(authMapper, times(1)).insert((AppModelAuth) any(AppModelAuth.class));
        verify(cacheManager).evictModelAuthCache("app-1");
    }

    @Test
    @DisplayName("addAuthorizations — new auth → sets appId, modelId, enabled=true")
    void addAuthorizations_setsCorrectFields() {
        when(authMapper.exists(any(LambdaQueryWrapper.class))).thenReturn(false);

        AppModelAuth template = new AppModelAuth();
        template.setQpsLimit(5);

        modelAuthService.addAuthorizations("app-1", List.of("m1"), template);

        verify(authMapper).insert(argThat((AppModelAuth inserted) ->
                "app-1".equals(inserted.getAppId())
                        && "m1".equals(inserted.getModelId())
                        && Boolean.TRUE.equals(inserted.getEnabled())
                        && Integer.valueOf(5).equals(inserted.getQpsLimit())
        ));
    }

    // -------------------------------------------------------------------------
    // update
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("update — auth found → updates and evicts cache")
    void update_updatesAndEvicts() {
        AppModelAuth existing = auth("app-1", "m1");
        when(authMapper.selectOne(any(LambdaQueryWrapper.class)))
                .thenReturn(existing)
                .thenReturn(existing);

        AppModelAuth updates = new AppModelAuth();
        modelAuthService.update("app-1", "m1", updates);

        verify(authMapper).update(eq(updates), any(LambdaQueryWrapper.class));
        verify(cacheManager).evictModelAuthCache("app-1");
        assertThat(updates.getUpdatedAt()).isNotNull();
    }

    @Test
    @DisplayName("update — auth not found → throws RESOURCE_NOT_FOUND")
    void update_notFound_throws() {
        when(authMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        assertThatThrownBy(() -> modelAuthService.update("app-1", "m1", new AppModelAuth()))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
    }

    // -------------------------------------------------------------------------
    // enable / disable
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("enable — auth found → sets enabled=true and evicts cache")
    void enable_updatesAndEvicts() {
        when(authMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(auth("app-1", "m1"));

        modelAuthService.enable("app-1", "m1");

        verify(authMapper).update(eq(null), any(LambdaUpdateWrapper.class));
        verify(cacheManager).evictModelAuthCache("app-1");
    }

    @Test
    @DisplayName("disable — auth found → sets enabled=false and evicts cache")
    void disable_updatesAndEvicts() {
        when(authMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(auth("app-1", "m1"));

        modelAuthService.disable("app-1", "m1");

        verify(authMapper).update(eq(null), any(LambdaUpdateWrapper.class));
        verify(cacheManager).evictModelAuthCache("app-1");
    }

    @Test
    @DisplayName("enable — auth not found → throws RESOURCE_NOT_FOUND")
    void enable_notFound_throws() {
        when(authMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        assertThatThrownBy(() -> modelAuthService.enable("app-1", "m1"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
    }

    // -------------------------------------------------------------------------
    // revoke
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("revoke — deletes auth and evicts cache")
    void revoke_deletesAndEvicts() {
        modelAuthService.revoke("app-1", "m1");

        verify(authMapper).delete(any(LambdaQueryWrapper.class));
        verify(cacheManager).evictModelAuthCache("app-1");
    }

    // -------------------------------------------------------------------------
    // helpers
    // -------------------------------------------------------------------------

    private AppModelAuth auth(String appId, String modelId) {
        AppModelAuth a = new AppModelAuth();
        a.setAppId(appId);
        a.setModelId(modelId);
        a.setEnabled(true);
        return a;
    }
}
