package com.pab.ficc.ibp.modelgate.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.pab.ficc.ibp.modelgate.server.cache.GatewayCacheManager;
import com.pab.ficc.ibp.modelgate.server.common.BusinessException;
import com.pab.ficc.ibp.modelgate.server.common.ErrorCode;
import com.pab.ficc.ibp.modelgate.server.crypto.CryptoService;
import com.pab.ficc.ibp.modelgate.server.domain.entity.ModelCredentialEntity;
import com.pab.ficc.ibp.modelgate.server.domain.entity.ModelInstanceEntity;
import com.pab.ficc.ibp.modelgate.server.mapper.AppModelAuthMapper;
import com.pab.ficc.ibp.modelgate.server.mapper.ModelCredentialMapper;
import com.pab.ficc.ibp.modelgate.server.mapper.ModelMapper;
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

    public List<ModelInstanceEntity> findAll() {
        return modelMapper.selectList(null);
    }

    public ModelInstanceEntity findByModelId(String modelId) {
        return Optional.ofNullable(modelMapper.selectOne(
                new LambdaQueryWrapper<ModelInstanceEntity>().eq(ModelInstanceEntity::getModelId, modelId)))
                .orElseThrow(() -> new BusinessException(ErrorCode.MODEL_NOT_FOUND, modelId));
    }

    public Optional<ModelInstanceEntity> getModelCached(String modelId) {
        return cacheManager.getModel(modelId, () -> Optional.ofNullable(
                modelMapper.selectOne(new LambdaQueryWrapper<ModelInstanceEntity>().eq(ModelInstanceEntity::getModelId, modelId))));
    }

    public ModelInstanceEntity create(ModelInstanceEntity model, ModelCredentialEntity credential) {
        boolean exists = modelMapper.exists(
                new LambdaQueryWrapper<ModelInstanceEntity>().eq(ModelInstanceEntity::getModelId, model.getModelId()));
        if (exists) {
            throw new BusinessException(ErrorCode.RESOURCE_ALREADY_EXISTS, "modelId: " + model.getModelId());
        }
        model.setCreateTime(LocalDateTime.now());
        model.setUpdateTime(LocalDateTime.now());
        modelMapper.insert(model);

        if (credential != null && credential.getAuthType() != null) {
            credential.setModelId(model.getModelId());
            if (credential.getSecretKey() != null) {
                credential.setSecretKey(cryptoService.encrypt(credential.getSecretKey()));
            }
            credential.setCreateTime(LocalDateTime.now());
            credential.setUpdateTime(LocalDateTime.now());
            credentialMapper.insert(credential);
        }
        return model;
    }

    public ModelInstanceEntity update(String modelId, ModelInstanceEntity updates) {
        findByModelId(modelId);
        updates.setModelId(modelId);
        updates.setUpdateTime(LocalDateTime.now());
        modelMapper.update(updates, new LambdaQueryWrapper<ModelInstanceEntity>().eq(ModelInstanceEntity::getModelId, modelId));
        cacheManager.evictModelCache(modelId);
        return findByModelId(modelId);
    }

    public void enable(String modelId) {
        findByModelId(modelId);
        modelMapper.update(null, new LambdaUpdateWrapper<ModelInstanceEntity>()
                .set(ModelInstanceEntity::getEnabled, true)
                .set(ModelInstanceEntity::getUpdateTime, LocalDateTime.now())
                .eq(ModelInstanceEntity::getModelId, modelId));
        cacheManager.evictModelCache(modelId);
    }

    public void disable(String modelId) {
        findByModelId(modelId);
        modelMapper.update(null, new LambdaUpdateWrapper<ModelInstanceEntity>()
                .set(ModelInstanceEntity::getEnabled, false)
                .set(ModelInstanceEntity::getUpdateTime, LocalDateTime.now())
                .eq(ModelInstanceEntity::getModelId, modelId));
        cacheManager.evictModelCache(modelId);
    }

    public void delete(String modelId) {
        findByModelId(modelId);
        boolean hasActiveAuths = authMapper.exists(
                new LambdaQueryWrapper<com.pab.ficc.ibp.modelgate.server.domain.entity.AppModelAuthEntity>()
                        .eq(com.pab.ficc.ibp.modelgate.server.domain.entity.AppModelAuthEntity::getModelId, modelId)
                        .eq(com.pab.ficc.ibp.modelgate.server.domain.entity.AppModelAuthEntity::getEnabled, true));
        if (hasActiveAuths) {
            throw new BusinessException(ErrorCode.ACTIVE_REFS_EXIST, "model has active app authorizations");
        }
        credentialMapper.delete(new LambdaQueryWrapper<ModelCredentialEntity>().eq(ModelCredentialEntity::getModelId, modelId));
        modelMapper.delete(new LambdaQueryWrapper<ModelInstanceEntity>().eq(ModelInstanceEntity::getModelId, modelId));
        cacheManager.evictModelCache(modelId);
    }

    public Optional<ModelCredentialEntity> getCredential(String modelId) {
        return Optional.ofNullable(credentialMapper.selectOne(
                new LambdaQueryWrapper<ModelCredentialEntity>().eq(ModelCredentialEntity::getModelId, modelId)));
    }

    public ModelCredentialEntity getCredentialDecrypted(String modelId) {
        return cacheManager.getModelCredential(modelId, () -> {
            ModelCredentialEntity cred = credentialMapper.selectOne(
                    new LambdaQueryWrapper<ModelCredentialEntity>().eq(ModelCredentialEntity::getModelId, modelId));
            if (cred != null && cred.getSecretKey() != null) {
                cred.setSecretKey(cryptoService.decrypt(cred.getSecretKey()));
            }
            return Optional.ofNullable(cred);
        }).orElse(null);
    }

    public void upsertCredential(String modelId, ModelCredentialEntity credential) {
        credential.setModelId(modelId);
        if (credential.getSecretKey() != null) {
            credential.setSecretKey(cryptoService.encrypt(credential.getSecretKey()));
        }
        boolean exists = credentialMapper.exists(
                new LambdaQueryWrapper<ModelCredentialEntity>().eq(ModelCredentialEntity::getModelId, modelId));
        if (exists) {
            credential.setUpdateTime(LocalDateTime.now());
            credentialMapper.update(credential,
                    new LambdaQueryWrapper<ModelCredentialEntity>().eq(ModelCredentialEntity::getModelId, modelId));
        } else {
            credential.setCreateTime(LocalDateTime.now());
            credential.setUpdateTime(LocalDateTime.now());
            credentialMapper.insert(credential);
        }
        cacheManager.evictModelCache(modelId);
        cacheManager.evictModelCredentialCache(modelId);
    }
}
