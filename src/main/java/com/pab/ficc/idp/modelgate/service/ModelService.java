package com.pab.ficc.idp.modelgate.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.pab.ficc.idp.modelgate.cache.GatewayCacheManager;
import com.pab.ficc.idp.modelgate.common.BusinessException;
import com.pab.ficc.idp.modelgate.common.ErrorCode;
import com.pab.ficc.idp.modelgate.crypto.CryptoService;
import com.pab.ficc.idp.modelgate.entity.ModelCredential;
import com.pab.ficc.idp.modelgate.entity.ModelInstance;
import com.pab.ficc.idp.modelgate.mapper.AppModelAuthMapper;
import com.pab.ficc.idp.modelgate.mapper.ModelCredentialMapper;
import com.pab.ficc.idp.modelgate.mapper.ModelMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class ModelService {

    private final ModelMapper modelMapper;
    private final ModelCredentialMapper credentialMapper;
    private final AppModelAuthMapper authMapper;
    private final CryptoService cryptoService;
    private final GatewayCacheManager cacheManager;

    public List<ModelInstance> findAll() {
        return modelMapper.selectList(null);
    }

    public ModelInstance findByModelId(String modelId) {
        return Optional.ofNullable(modelMapper.selectOne(
                new LambdaQueryWrapper<ModelInstance>().eq(ModelInstance::getModelId, modelId)))
                .orElseThrow(() -> new BusinessException(ErrorCode.MODEL_NOT_FOUND, modelId));
    }

    public Optional<ModelInstance> getModelCached(String modelId) {
        return cacheManager.getModel(modelId, () -> Optional.ofNullable(
                modelMapper.selectOne(new LambdaQueryWrapper<ModelInstance>().eq(ModelInstance::getModelId, modelId))));
    }

    public ModelInstance create(ModelInstance model, ModelCredential credential) {
        boolean exists = modelMapper.exists(
                new LambdaQueryWrapper<ModelInstance>().eq(ModelInstance::getModelId, model.getModelId()));
        if (exists) {
            throw new BusinessException(ErrorCode.RESOURCE_ALREADY_EXISTS, "modelId: " + model.getModelId());
        }
        model.setCreatedAt(LocalDateTime.now());
        model.setUpdatedAt(LocalDateTime.now());
        modelMapper.insert(model);

        if (credential != null && credential.getAuthType() != null) {
            credential.setModelId(model.getModelId());
            if (credential.getSecretKey() != null) {
                credential.setSecretKey(cryptoService.encrypt(credential.getSecretKey()));
            }
            credential.setCreatedAt(LocalDateTime.now());
            credential.setUpdatedAt(LocalDateTime.now());
            credentialMapper.insert(credential);
        }
        return model;
    }

    public ModelInstance update(String modelId, ModelInstance updates) {
        findByModelId(modelId);
        updates.setModelId(modelId);
        updates.setUpdatedAt(LocalDateTime.now());
        modelMapper.update(updates, new LambdaQueryWrapper<ModelInstance>().eq(ModelInstance::getModelId, modelId));
        cacheManager.evictModelCache(modelId);
        return findByModelId(modelId);
    }

    public void enable(String modelId) {
        findByModelId(modelId);
        modelMapper.update(null, new LambdaUpdateWrapper<ModelInstance>()
                .set(ModelInstance::getEnabled, true)
                .set(ModelInstance::getUpdatedAt, LocalDateTime.now())
                .eq(ModelInstance::getModelId, modelId));
        cacheManager.evictModelCache(modelId);
    }

    public void disable(String modelId) {
        findByModelId(modelId);
        modelMapper.update(null, new LambdaUpdateWrapper<ModelInstance>()
                .set(ModelInstance::getEnabled, false)
                .set(ModelInstance::getUpdatedAt, LocalDateTime.now())
                .eq(ModelInstance::getModelId, modelId));
        cacheManager.evictModelCache(modelId);
    }

    public void delete(String modelId) {
        findByModelId(modelId);
        boolean hasActiveAuths = authMapper.exists(
                new LambdaQueryWrapper<com.pab.ficc.idp.modelgate.entity.AppModelAuth>()
                        .eq(com.pab.ficc.idp.modelgate.entity.AppModelAuth::getModelId, modelId)
                        .eq(com.pab.ficc.idp.modelgate.entity.AppModelAuth::getEnabled, true));
        if (hasActiveAuths) {
            throw new BusinessException(ErrorCode.ACTIVE_REFS_EXIST, "model has active app authorizations");
        }
        credentialMapper.delete(new LambdaQueryWrapper<ModelCredential>().eq(ModelCredential::getModelId, modelId));
        modelMapper.delete(new LambdaQueryWrapper<ModelInstance>().eq(ModelInstance::getModelId, modelId));
        cacheManager.evictModelCache(modelId);
    }

    public Optional<ModelCredential> getCredential(String modelId) {
        return Optional.ofNullable(credentialMapper.selectOne(
                new LambdaQueryWrapper<ModelCredential>().eq(ModelCredential::getModelId, modelId)));
    }

    public ModelCredential getCredentialDecrypted(String modelId) {
        ModelCredential cred = credentialMapper.selectOne(
                new LambdaQueryWrapper<ModelCredential>().eq(ModelCredential::getModelId, modelId));
        if (cred != null && cred.getSecretKey() != null) {
            cred.setSecretKey(cryptoService.decrypt(cred.getSecretKey()));
        }
        return cred;
    }

    public void upsertCredential(String modelId, ModelCredential credential) {
        credential.setModelId(modelId);
        if (credential.getSecretKey() != null) {
            credential.setSecretKey(cryptoService.encrypt(credential.getSecretKey()));
        }
        boolean exists = credentialMapper.exists(
                new LambdaQueryWrapper<ModelCredential>().eq(ModelCredential::getModelId, modelId));
        if (exists) {
            credential.setUpdatedAt(LocalDateTime.now());
            credentialMapper.update(credential,
                    new LambdaQueryWrapper<ModelCredential>().eq(ModelCredential::getModelId, modelId));
        } else {
            credential.setCreatedAt(LocalDateTime.now());
            credential.setUpdatedAt(LocalDateTime.now());
            credentialMapper.insert(credential);
        }
        cacheManager.evictModelCache(modelId);
    }
}
