package com.pab.ficc.ibp.modelgate.server.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.pab.ficc.ibp.modelgate.server.cache.GatewayCacheManager;
import com.pab.ficc.ibp.modelgate.server.common.BusinessException;
import com.pab.ficc.ibp.modelgate.server.common.ErrorCode;
import com.pab.ficc.ibp.modelgate.server.crypto.CryptoService;
import com.pab.ficc.ibp.modelgate.server.domain.entity.ModelCredential;
import com.pab.ficc.ibp.modelgate.server.domain.entity.ModelInstance;
import com.pab.ficc.ibp.modelgate.server.mapper.AppModelAuthMapper;
import com.pab.ficc.ibp.modelgate.server.mapper.ModelCredentialMapper;
import com.pab.ficc.ibp.modelgate.server.mapper.ModelMapper;
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
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ModelServiceTest {

    @Mock ModelMapper modelMapper;
    @Mock ModelCredentialMapper credentialMapper;
    @Mock AppModelAuthMapper authMapper;
    @Mock CryptoService cryptoService;
    @Mock GatewayCacheManager cacheManager;

    @InjectMocks ModelService modelService;

    @BeforeAll
    static void initMybatisPlus() {
        // 初始化 MyBatis Plus lambda 缓存，使 LambdaUpdateWrapper 内的方法引用能正确解析列名
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), ModelInstance.class);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), ModelCredential.class);
    }

    // -------------------------------------------------------------------------
    // findAll
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("findAll — returns list from mapper")
    void findAll_returnsList() {
        ModelInstance m = model("gpt-4");
        when(modelMapper.selectList(null)).thenReturn(List.of(m));

        List<ModelInstance> result = modelService.findAll();

        assertThat(result).containsExactly(m);
    }

    // -------------------------------------------------------------------------
    // findByModelId
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("findByModelId — found → returns entity")
    void findByModelId_found() {
        ModelInstance m = model("gpt-4");
        when(modelMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(m);

        ModelInstance result = modelService.findByModelId("gpt-4");

        assertThat(result.getModelId()).isEqualTo("gpt-4");
    }

    @Test
    @DisplayName("findByModelId — not found → throws BusinessException MODEL_NOT_FOUND")
    void findByModelId_notFound_throws() {
        when(modelMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        assertThatThrownBy(() -> modelService.findByModelId("unknown"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.MODEL_NOT_FOUND));
    }

    // -------------------------------------------------------------------------
    // getModelCached
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("getModelCached — delegates to cacheManager")
    void getModelCached_delegatesToCache() {
        ModelInstance m = model("claude-3");
        when(cacheManager.getModel(eq("claude-3"), any())).thenReturn(Optional.of(m));

        Optional<ModelInstance> result = modelService.getModelCached("claude-3");

        assertThat(result).contains(m);
    }

    // -------------------------------------------------------------------------
    // create
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("create — new model without credential → inserts model only")
    void create_newModel_noCredential() {
        when(modelMapper.exists(any(LambdaQueryWrapper.class))).thenReturn(false);

        ModelInstance m = model("new-model");
        ModelInstance result = modelService.create(m, null);

        verify(modelMapper).insert((ModelInstance) m);
        verifyNoInteractions(credentialMapper);
        assertThat(result.getCreatedAt()).isNotNull();
        assertThat(result.getUpdatedAt()).isNotNull();
    }

    @Test
    @DisplayName("create — new model with credential → encrypts secret and inserts both")
    void create_newModel_withCredential() {
        when(modelMapper.exists(any(LambdaQueryWrapper.class))).thenReturn(false);
        when(cryptoService.encrypt("plain-secret")).thenReturn("enc-secret");

        ModelInstance m = model("new-model");
        ModelCredential cred = new ModelCredential();
        cred.setAuthType("api_key");
        cred.setSecretKey("plain-secret");

        modelService.create(m, cred);

        verify(modelMapper).insert((ModelInstance) m);
        verify(credentialMapper).insert((ModelCredential) cred);
        assertThat(cred.getSecretKey()).isEqualTo("enc-secret");
        assertThat(cred.getModelId()).isEqualTo("new-model");
    }

    @Test
    @DisplayName("create — duplicate modelId → throws RESOURCE_ALREADY_EXISTS")
    void create_duplicate_throws() {
        when(modelMapper.exists(any(LambdaQueryWrapper.class))).thenReturn(true);

        assertThatThrownBy(() -> modelService.create(model("dup"), null))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.RESOURCE_ALREADY_EXISTS));

        verify(modelMapper, never()).insert((ModelInstance) any());
    }

    @Test
    @DisplayName("create — credential with null authType → skips credential insert")
    void create_credentialWithNullAuthType_skipsInsert() {
        when(modelMapper.exists(any(LambdaQueryWrapper.class))).thenReturn(false);

        ModelCredential cred = new ModelCredential();
        // authType is null

        modelService.create(model("m1"), cred);

        verify(modelMapper).insert((ModelInstance) any());
        verifyNoInteractions(credentialMapper);
    }

    // -------------------------------------------------------------------------
    // update
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("update — evicts cache and returns refreshed entity")
    void update_evictsAndReturns() {
        ModelInstance existing = model("m1");
        when(modelMapper.selectOne(any(LambdaQueryWrapper.class)))
                .thenReturn(existing)
                .thenReturn(existing);

        ModelInstance updates = new ModelInstance();
        modelService.update("m1", updates);

        verify(cacheManager).evictModelCache("m1");
        assertThat(updates.getModelId()).isEqualTo("m1");
    }

    // -------------------------------------------------------------------------
    // enable / disable
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("enable — verifies model exists then updates enabled flag")
    void enable_updatesFlag() {
        when(modelMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(model("m1"));

        modelService.enable("m1");

        verify(modelMapper).update(eq(null), any(LambdaUpdateWrapper.class));
        verify(cacheManager).evictModelCache("m1");
    }

    @Test
    @DisplayName("disable — verifies model exists then updates enabled flag")
    void disable_updatesFlag() {
        when(modelMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(model("m1"));

        modelService.disable("m1");

        verify(modelMapper).update(eq(null), any(LambdaUpdateWrapper.class));
        verify(cacheManager).evictModelCache("m1");
    }

    @Test
    @DisplayName("enable — model not found → throws MODEL_NOT_FOUND")
    void enable_notFound_throws() {
        when(modelMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        assertThatThrownBy(() -> modelService.enable("missing"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.MODEL_NOT_FOUND));
    }

    // -------------------------------------------------------------------------
    // delete
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("delete — no active auths → deletes credential and model")
    void delete_noActiveAuths_deletesSuccessfully() {
        when(modelMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(model("m1"));
        when(authMapper.exists(any(LambdaQueryWrapper.class))).thenReturn(false);

        modelService.delete("m1");

        verify(credentialMapper).delete(any(LambdaQueryWrapper.class));
        verify(modelMapper).delete(any(LambdaQueryWrapper.class));
        verify(cacheManager).evictModelCache("m1");
    }

    @Test
    @DisplayName("delete — has active auths → throws ACTIVE_REFS_EXIST")
    void delete_activeAuths_throws() {
        when(modelMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(model("m1"));
        when(authMapper.exists(any(LambdaQueryWrapper.class))).thenReturn(true);

        assertThatThrownBy(() -> modelService.delete("m1"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.ACTIVE_REFS_EXIST));

        verify(modelMapper, never()).delete(any(LambdaQueryWrapper.class));
    }

    // -------------------------------------------------------------------------
    // getCredential / getCredentialDecrypted
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("getCredential — delegates to mapper")
    void getCredential_returnsOptional() {
        ModelCredential cred = new ModelCredential();
        when(credentialMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(cred);

        Optional<ModelCredential> result = modelService.getCredential("m1");

        assertThat(result).contains(cred);
    }

    @Test
    @DisplayName("getCredentialDecrypted — decrypts secretKey")
    void getCredentialDecrypted_decryptsKey() {
        ModelCredential cred = new ModelCredential();
        cred.setSecretKey("encrypted");
        when(credentialMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(cred);
        when(cryptoService.decrypt("encrypted")).thenReturn("plain");

        ModelCredential result = modelService.getCredentialDecrypted("m1");

        assertThat(result.getSecretKey()).isEqualTo("plain");
    }

    // -------------------------------------------------------------------------
    // upsertCredential
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("upsertCredential — new credential → inserts with encrypted key")
    void upsertCredential_insert() {
        when(cryptoService.encrypt("raw")).thenReturn("enc");
        when(credentialMapper.exists(any(LambdaQueryWrapper.class))).thenReturn(false);

        ModelCredential cred = new ModelCredential();
        cred.setSecretKey("raw");

        modelService.upsertCredential("m1", cred);

        verify(credentialMapper).insert((ModelCredential) cred);
        assertThat(cred.getSecretKey()).isEqualTo("enc");
        verify(cacheManager).evictModelCache("m1");
    }

    @Test
    @DisplayName("upsertCredential — existing credential → updates")
    void upsertCredential_update() {
        when(cryptoService.encrypt("raw")).thenReturn("enc");
        when(credentialMapper.exists(any(LambdaQueryWrapper.class))).thenReturn(true);

        ModelCredential cred = new ModelCredential();
        cred.setSecretKey("raw");

        modelService.upsertCredential("m1", cred);

        verify(credentialMapper).update(eq(cred), any(LambdaQueryWrapper.class));
        verify(credentialMapper, never()).insert((ModelCredential) any());
    }

    // -------------------------------------------------------------------------
    // helpers
    // -------------------------------------------------------------------------

    private ModelInstance model(String modelId) {
        ModelInstance m = new ModelInstance();
        m.setModelId(modelId);
        m.setModelName("Test Model");
        m.setEnabled(true);
        m.setCreatedAt(LocalDateTime.now());
        m.setUpdatedAt(LocalDateTime.now());
        return m;
    }
}
