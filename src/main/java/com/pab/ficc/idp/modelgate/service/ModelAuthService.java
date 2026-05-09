package com.pab.ficc.idp.modelgate.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.pab.ficc.idp.modelgate.cache.GatewayCacheManager;
import com.pab.ficc.idp.modelgate.common.BusinessException;
import com.pab.ficc.idp.modelgate.common.ErrorCode;
import com.pab.ficc.idp.modelgate.entity.AppModelAuth;
import com.pab.ficc.idp.modelgate.mapper.AppModelAuthMapper;
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

    public List<AppModelAuth> listByAppId(String appId) {
        return authMapper.selectList(
                new LambdaQueryWrapper<AppModelAuth>().eq(AppModelAuth::getAppId, appId)
                        .orderByDesc(AppModelAuth::getCreatedAt));
    }

    public List<AppModelAuth> listByAppIdCached(String appId) {
        return cacheManager.getModelAuths(appId, () -> listByAppId(appId));
    }

    public void addAuthorizations(String appId, List<String> modelIds, AppModelAuth template) {
        for (String modelId : modelIds) {
            boolean exists = authMapper.exists(new LambdaQueryWrapper<AppModelAuth>()
                    .eq(AppModelAuth::getAppId, appId).eq(AppModelAuth::getModelId, modelId));
            if (exists) continue;

            AppModelAuth auth = new AppModelAuth();
            auth.setAppId(appId);
            auth.setModelId(modelId);
            auth.setEnabled(true);
            auth.setQpsLimit(template.getQpsLimit());
            auth.setDailyLimit(template.getDailyLimit());
            auth.setMonthlyTokens(template.getMonthlyTokens());
            auth.setExpiresAt(template.getExpiresAt());
            auth.setCreatedAt(LocalDateTime.now());
            auth.setUpdatedAt(LocalDateTime.now());
            authMapper.insert(auth);
        }
        cacheManager.evictModelAuthCache(appId);
    }

    public AppModelAuth update(String appId, String modelId, AppModelAuth updates) {
        getAuthOrThrow(appId, modelId);
        updates.setUpdatedAt(LocalDateTime.now());
        authMapper.update(updates, new LambdaQueryWrapper<AppModelAuth>()
                .eq(AppModelAuth::getAppId, appId).eq(AppModelAuth::getModelId, modelId));
        cacheManager.evictModelAuthCache(appId);
        return getAuthOrThrow(appId, modelId);
    }

    public void enable(String appId, String modelId) {
        getAuthOrThrow(appId, modelId);
        authMapper.update(null, new LambdaUpdateWrapper<AppModelAuth>()
                .set(AppModelAuth::getEnabled, true)
                .set(AppModelAuth::getUpdatedAt, LocalDateTime.now())
                .eq(AppModelAuth::getAppId, appId).eq(AppModelAuth::getModelId, modelId));
        cacheManager.evictModelAuthCache(appId);
    }

    public void disable(String appId, String modelId) {
        getAuthOrThrow(appId, modelId);
        authMapper.update(null, new LambdaUpdateWrapper<AppModelAuth>()
                .set(AppModelAuth::getEnabled, false)
                .set(AppModelAuth::getUpdatedAt, LocalDateTime.now())
                .eq(AppModelAuth::getAppId, appId).eq(AppModelAuth::getModelId, modelId));
        cacheManager.evictModelAuthCache(appId);
    }

    public void revoke(String appId, String modelId) {
        authMapper.delete(new LambdaQueryWrapper<AppModelAuth>()
                .eq(AppModelAuth::getAppId, appId).eq(AppModelAuth::getModelId, modelId));
        cacheManager.evictModelAuthCache(appId);
    }

    private AppModelAuth getAuthOrThrow(String appId, String modelId) {
        return Optional.ofNullable(authMapper.selectOne(new LambdaQueryWrapper<AppModelAuth>()
                .eq(AppModelAuth::getAppId, appId).eq(AppModelAuth::getModelId, modelId)))
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,
                        "auth: appId=" + appId + " modelId=" + modelId));
    }
}
