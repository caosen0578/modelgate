package com.pab.ficc.ibp.modelgate.server.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.pab.ficc.ibp.modelgate.server.common.BusinessException;
import com.pab.ficc.ibp.modelgate.server.common.ErrorCode;
import com.pab.ficc.ibp.modelgate.server.domain.entity.AccessRule;
import com.pab.ficc.ibp.modelgate.server.domain.entity.AppCredential;
import com.pab.ficc.ibp.modelgate.server.domain.entity.AppInfo;
import com.pab.ficc.ibp.modelgate.server.domain.entity.AppModelAuth;
import com.pab.ficc.ibp.modelgate.server.mapper.AccessRuleMapper;
import com.pab.ficc.ibp.modelgate.server.mapper.AppCredentialMapper;
import com.pab.ficc.ibp.modelgate.server.mapper.AppMapper;
import com.pab.ficc.ibp.modelgate.server.mapper.AppModelAuthMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AppServiceTest {

    @Mock AppMapper appMapper;
    @Mock AppCredentialMapper credentialMapper;
    @Mock AppModelAuthMapper authMapper;
    @Mock AccessRuleMapper accessRuleMapper;

    @InjectMocks AppService appService;

    @BeforeAll
    static void initMybatisPlus() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), AppInfo.class);
    }

    // -------------------------------------------------------------------------
    // findAll
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("findAll — returns list from mapper")
    void findAll_returnsList() {
        AppInfo a = app("app-1");
        when(appMapper.selectList(null)).thenReturn(List.of(a));

        assertThat(appService.findAll()).containsExactly(a);
    }

    // -------------------------------------------------------------------------
    // findByAppId
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("findByAppId — found → returns entity")
    void findByAppId_found() {
        AppInfo a = app("app-1");
        when(appMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(a);

        assertThat(appService.findByAppId("app-1")).isEqualTo(a);
    }

    @Test
    @DisplayName("findByAppId — not found → throws RESOURCE_NOT_FOUND")
    void findByAppId_notFound_throws() {
        when(appMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        assertThatThrownBy(() -> appService.findByAppId("ghost"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
    }

    // -------------------------------------------------------------------------
    // create
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("create — new app → sets enabled=true and timestamps")
    void create_newApp_setsFields() {
        when(appMapper.exists(any(LambdaQueryWrapper.class))).thenReturn(false);

        AppInfo a = new AppInfo();
        a.setAppId("app-new");
        a.setAppName("New App");

        AppInfo result = appService.create(a);

        verify(appMapper).insert((AppInfo) a);
        assertThat(result.getEnabled()).isTrue();
        assertThat(result.getCreatedAt()).isNotNull();
        assertThat(result.getUpdatedAt()).isNotNull();
    }

    @Test
    @DisplayName("create — duplicate appId → throws RESOURCE_ALREADY_EXISTS")
    void create_duplicate_throws() {
        when(appMapper.exists(any(LambdaQueryWrapper.class))).thenReturn(true);

        assertThatThrownBy(() -> appService.create(app("dup")))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.RESOURCE_ALREADY_EXISTS));

        verify(appMapper, never()).insert((AppInfo) any());
    }

    // -------------------------------------------------------------------------
    // update
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("update — sets updatedAt and calls mapper update")
    void update_updatesApp() {
        AppInfo existing = app("app-1");
        when(appMapper.selectOne(any(LambdaQueryWrapper.class)))
                .thenReturn(existing)
                .thenReturn(existing);

        AppInfo updates = new AppInfo();
        appService.update("app-1", updates);

        verify(appMapper).update(eq(updates), any(LambdaQueryWrapper.class));
        assertThat(updates.getUpdatedAt()).isNotNull();
    }

    // -------------------------------------------------------------------------
    // enable / disable
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("enable — verifies app exists then updates enabled flag")
    void enable_updatesFlag() {
        when(appMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(app("app-1"));

        appService.enable("app-1");

        verify(appMapper).update(eq(null), any(LambdaUpdateWrapper.class));
    }

    @Test
    @DisplayName("disable — verifies app exists then clears enabled flag")
    void disable_updatesFlag() {
        when(appMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(app("app-1"));

        appService.disable("app-1");

        verify(appMapper).update(eq(null), any(LambdaUpdateWrapper.class));
    }

    @Test
    @DisplayName("enable — app not found → throws RESOURCE_NOT_FOUND")
    void enable_notFound_throws() {
        when(appMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        assertThatThrownBy(() -> appService.enable("ghost"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
    }

    // -------------------------------------------------------------------------
    // delete (cascade)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("delete — cascades through access rules, auths, credentials, then app")
    void delete_cascadeDeletesAllRelated() {
        when(appMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(app("app-1"));

        appService.delete("app-1");

        verify(accessRuleMapper).delete(any(LambdaQueryWrapper.class));
        verify(authMapper).delete(any(LambdaQueryWrapper.class));
        verify(credentialMapper).delete(any(LambdaQueryWrapper.class));
        verify(appMapper).delete(any(LambdaQueryWrapper.class));
    }

    @Test
    @DisplayName("delete — app not found → throws before any delete")
    void delete_notFound_throws() {
        when(appMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        assertThatThrownBy(() -> appService.delete("ghost"))
                .isInstanceOf(BusinessException.class);

        verifyNoInteractions(accessRuleMapper, authMapper, credentialMapper);
        verify(appMapper, never()).delete(any(LambdaQueryWrapper.class));
    }

    // -------------------------------------------------------------------------
    // helpers
    // -------------------------------------------------------------------------

    private AppInfo app(String appId) {
        AppInfo a = new AppInfo();
        a.setAppId(appId);
        a.setAppName("Test App");
        a.setEnabled(true);
        a.setCreatedAt(LocalDateTime.now());
        a.setUpdatedAt(LocalDateTime.now());
        return a;
    }
}
