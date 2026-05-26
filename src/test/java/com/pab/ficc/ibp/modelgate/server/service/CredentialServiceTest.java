package com.pab.ficc.ibp.modelgate.server.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.pab.ficc.ibp.modelgate.server.cache.GatewayCacheManager;
import com.pab.ficc.ibp.modelgate.server.common.BusinessException;
import com.pab.ficc.ibp.modelgate.server.common.ErrorCode;
import com.pab.ficc.ibp.modelgate.server.crypto.CryptoService;
import com.pab.ficc.ibp.modelgate.server.domain.entity.AppCredential;
import com.pab.ficc.ibp.modelgate.server.mapper.AppCredentialMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.Serializable;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CredentialServiceTest {

    @Mock AppCredentialMapper credentialMapper;
    @Mock CryptoService cryptoService;
    @Mock GatewayCacheManager cacheManager;

    @InjectMocks CredentialService credentialService;

    @BeforeAll
    static void initMybatisPlus() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), AppCredential.class);
    }

    // -------------------------------------------------------------------------
    // listByAppId
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("listByAppId — delegates to mapper")
    void listByAppId_returnsList() {
        AppCredential c = cred(1L, "app-1", "hash1");
        when(credentialMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(c));

        assertThat(credentialService.listByAppId("app-1")).containsExactly(c);
    }

    // -------------------------------------------------------------------------
    // create — api_key type
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("create — api_key type → generates mg- prefixed key, stores hash")
    void create_apiKeyType_generatesKey() {
        when(cryptoService.sha256Hex(anyString())).thenReturn("hash-abc");

        AppCredential req = new AppCredential();
        req.setCredType("api_key");
        req.setCredName("my-key");

        CredentialService.CreatedCredential result = credentialService.create("app-1", req);

        verify(credentialMapper).insert((AppCredential) any(AppCredential.class));
        verify(cacheManager).evictCredentialCacheByAppId("app-1");

        assertThat(result.plainSecret()).startsWith("mg-app-1-");
        assertThat(result.credential().getCredType()).isEqualTo("api_key");
        assertThat(result.credential().getSecretHash()).isEqualTo("hash-abc");
        assertThat(result.credential().getEnabled()).isTrue();
    }

    // -------------------------------------------------------------------------
    // create — ak_sk type
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("create — ak_sk type → generates AK/SK pair, encrypts SK")
    void create_akSkType_generatesAkSk() {
        when(cryptoService.sha256Hex(anyString())).thenReturn("hash-sk");
        when(cryptoService.encrypt(anyString())).thenReturn("enc-sk");

        AppCredential req = new AppCredential();
        req.setCredType("ak_sk");
        req.setCredName("aksk-cred");

        CredentialService.CreatedCredential result = credentialService.create("app-1", req);

        verify(credentialMapper).insert((AppCredential) any(AppCredential.class));
        verify(cacheManager).evictCredentialCacheByAppId("app-1");

        assertThat(result.credential().getAccessKey()).startsWith("AK_");
        assertThat(result.plainSecret()).startsWith("SK_");
        assertThat(result.credential().getSecretHash()).isEqualTo("hash-sk");
        assertThat(result.credential().getSecretEncrypted()).isEqualTo("enc-sk");
    }

    // -------------------------------------------------------------------------
    // findBySecretHash
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("findBySecretHash — delegates to cacheManager")
    void findBySecretHash_delegatesToCache() {
        AppCredential c = cred(1L, "app-1", "myhash");
        when(cacheManager.getCredential(eq("myhash"), any())).thenReturn(Optional.of(c));

        Optional<AppCredential> result = credentialService.findBySecretHash("myhash");

        assertThat(result).contains(c);
    }

    // -------------------------------------------------------------------------
    // findByAccessKey
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("findByAccessKey — returns empty when not found")
    void findByAccessKey_notFound() {
        when(credentialMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        assertThat(credentialService.findByAccessKey("AK_missing")).isEmpty();
    }

    @Test
    @DisplayName("findByAccessKey — returns credential when found")
    void findByAccessKey_found() {
        AppCredential c = cred(1L, "app-1", "h1");
        c.setAccessKey("AK_app_1_abc");
        when(credentialMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(c);

        assertThat(credentialService.findByAccessKey("AK_app_1_abc")).contains(c);
    }

    // -------------------------------------------------------------------------
    // enable / disable
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("enable — credential found and belongs to app → updates and evicts cache")
    void enable_updatesAndEvicts() {
        AppCredential c = cred(10L, "app-1", "myhash");
        when(credentialMapper.selectById(10L)).thenReturn(c);

        credentialService.enable("app-1", 10L);

        verify(credentialMapper).update(eq(null), any(LambdaUpdateWrapper.class));
        verify(cacheManager).evictCredentialCache("myhash");
    }

    @Test
    @DisplayName("enable — credential belongs to different app → throws RESOURCE_NOT_FOUND")
    void enable_wrongApp_throws() {
        AppCredential c = cred(10L, "other-app", "myhash");
        when(credentialMapper.selectById(10L)).thenReturn(c);

        assertThatThrownBy(() -> credentialService.enable("app-1", 10L))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
    }

    @Test
    @DisplayName("disable — credential found → updates and evicts cache")
    void disable_updatesAndEvicts() {
        AppCredential c = cred(10L, "app-1", "myhash");
        when(credentialMapper.selectById(10L)).thenReturn(c);

        credentialService.disable("app-1", 10L);

        verify(credentialMapper).update(eq(null), any(LambdaUpdateWrapper.class));
        verify(cacheManager).evictCredentialCache("myhash");
    }

    // -------------------------------------------------------------------------
    // delete
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("delete — credential found → deletes and evicts cache")
    void delete_deletesAndEvicts() {
        AppCredential c = cred(10L, "app-1", "myhash");
        when(credentialMapper.selectById(10L)).thenReturn(c);

        credentialService.delete("app-1", 10L);

        verify(credentialMapper).deleteById((Serializable) 10L);
        verify(cacheManager).evictCredentialCache("myhash");
    }

    @Test
    @DisplayName("delete — credential not found → throws RESOURCE_NOT_FOUND")
    void delete_notFound_throws() {
        when(credentialMapper.selectById(99L)).thenReturn(null);

        assertThatThrownBy(() -> credentialService.delete("app-1", 99L))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));

        verify(credentialMapper, never()).deleteById((Serializable) any());
    }

    // -------------------------------------------------------------------------
    // helpers
    // -------------------------------------------------------------------------

    private AppCredential cred(Long id, String appId, String secretHash) {
        AppCredential c = new AppCredential();
        c.setId(id);
        c.setAppId(appId);
        c.setSecretHash(secretHash);
        c.setEnabled(true);
        return c;
    }
}
