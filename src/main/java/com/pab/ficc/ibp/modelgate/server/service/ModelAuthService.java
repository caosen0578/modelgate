package com.pab.ficc.ibp.modelgate.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.pab.ficc.ibp.modelgate.server.cache.GatewayCacheManager;
import com.pab.ficc.ibp.modelgate.server.common.BusinessException;
import com.pab.ficc.ibp.modelgate.server.common.ErrorCode;
import com.pab.ficc.ibp.modelgate.server.domain.entity.AppModelAuthEntity;
import com.pab.ficc.ibp.modelgate.server.mapper.AppModelAuthMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class ModelAuthService {

    private final AppModelAuthMapper authMapper;
    private final GatewayCacheManager cacheManager;

    public List<AppModelAuthEntity> listByAppId(String appId) {
        return authMapper.selectList(
                new LambdaQueryWrapper<AppModelAuthEntity>().eq(AppModelAuthEntity::getAppId, appId)
                        .orderByDesc(AppModelAuthEntity::getCreateTime));
    }

    public List<AppModelAuthEntity> listByAppIdCached(String appId) {
        return cacheManager.getModelAuths(appId, () -> listByAppId(appId));
    }

    public void addAuthorizations(String appId, List<String> modelIds, AppModelAuthEntity template) {
        for (String modelId : modelIds) {
            boolean exists = authMapper.exists(new LambdaQueryWrapper<AppModelAuthEntity>()
                    .eq(AppModelAuthEntity::getAppId, appId).eq(AppModelAuthEntity::getModelId, modelId));
            if (exists) continue;

            AppModelAuthEntity auth = new AppModelAuthEntity();
            auth.setAppId(appId);
            auth.setModelId(modelId);
            auth.setEnabled(true);
            auth.setQpsLimit(template.getQpsLimit());
            auth.setDailyLimit(template.getDailyLimit());
            auth.setMonthlyTokens(template.getMonthlyTokens());
            auth.setExpiresAt(template.getExpiresAt());
            auth.setCreateTime(LocalDateTime.now());
            auth.setUpdateTime(LocalDateTime.now());
            authMapper.insert(auth);
        }
        cacheManager.evictModelAuthCache(appId);
    }

    public AppModelAuthEntity update(String appId, String modelId, AppModelAuthEntity updates) {
        getAuthOrThrow(appId, modelId);
        updates.setUpdateTime(LocalDateTime.now());
        authMapper.update(updates, new LambdaQueryWrapper<AppModelAuthEntity>()
                .eq(AppModelAuthEntity::getAppId, appId).eq(AppModelAuthEntity::getModelId, modelId));
        cacheManager.evictModelAuthCache(appId);
        return getAuthOrThrow(appId, modelId);
    }

    public void enable(String appId, String modelId) {
        getAuthOrThrow(appId, modelId);
        authMapper.update(null, new LambdaUpdateWrapper<AppModelAuthEntity>()
                .set(AppModelAuthEntity::getEnabled, true)
                .set(AppModelAuthEntity::getUpdateTime, LocalDateTime.now())
                .eq(AppModelAuthEntity::getAppId, appId).eq(AppModelAuthEntity::getModelId, modelId));
        cacheManager.evictModelAuthCache(appId);
    }

    public void disable(String appId, String modelId) {
        getAuthOrThrow(appId, modelId);
        authMapper.update(null, new LambdaUpdateWrapper<AppModelAuthEntity>()
                .set(AppModelAuthEntity::getEnabled, false)
                .set(AppModelAuthEntity::getUpdateTime, LocalDateTime.now())
                .eq(AppModelAuthEntity::getAppId, appId).eq(AppModelAuthEntity::getModelId, modelId));
        cacheManager.evictModelAuthCache(appId);
    }

    public void revoke(String appId, String modelId) {
        authMapper.delete(new LambdaQueryWrapper<AppModelAuthEntity>()
                .eq(AppModelAuthEntity::getAppId, appId).eq(AppModelAuthEntity::getModelId, modelId));
        cacheManager.evictModelAuthCache(appId);
    }

    private AppModelAuthEntity getAuthOrThrow(String appId, String modelId) {
        return Optional.ofNullable(authMapper.selectOne(new LambdaQueryWrapper<AppModelAuthEntity>()
                .eq(AppModelAuthEntity::getAppId, appId).eq(AppModelAuthEntity::getModelId, modelId)))
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,
                        "auth: appId=" + appId + " modelId=" + modelId));
    }
}
