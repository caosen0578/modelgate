package com.pab.ficc.ibp.modelgate.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.pab.ficc.ibp.modelgate.server.common.BusinessException;
import com.pab.ficc.ibp.modelgate.server.common.ErrorCode;
import com.pab.ficc.ibp.modelgate.server.domain.entity.AccessRuleEntity;
import com.pab.ficc.ibp.modelgate.server.domain.entity.AppCredentialEntity;
import com.pab.ficc.ibp.modelgate.server.domain.entity.AppInfoEntity;
import com.pab.ficc.ibp.modelgate.server.domain.entity.AppModelAuthEntity;
import com.pab.ficc.ibp.modelgate.server.mapper.AccessRuleMapper;
import com.pab.ficc.ibp.modelgate.server.mapper.AppCredentialMapper;
import com.pab.ficc.ibp.modelgate.server.mapper.AppMapper;
import com.pab.ficc.ibp.modelgate.server.mapper.AppModelAuthMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class AppService {

    private final AppMapper appMapper;
    private final AppCredentialMapper credentialMapper;
    private final AppModelAuthMapper authMapper;
    private final AccessRuleMapper accessRuleMapper;

    public List<AppInfoEntity> findAll() {
        return appMapper.selectList(null);
    }

    public AppInfoEntity findByAppId(String appId) {
        return Optional.ofNullable(appMapper.selectOne(
                new LambdaQueryWrapper<AppInfoEntity>().eq(AppInfoEntity::getAppId, appId)))
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "appId: " + appId));
    }

    public AppInfoEntity create(AppInfoEntity app) {
        boolean exists = appMapper.exists(new LambdaQueryWrapper<AppInfoEntity>().eq(AppInfoEntity::getAppId, app.getAppId()));
        if (exists) {
            throw new BusinessException(ErrorCode.RESOURCE_ALREADY_EXISTS, "appId: " + app.getAppId());
        }
        app.setCreateTime(LocalDateTime.now());
        app.setUpdateTime(LocalDateTime.now());
        app.setEnabled(true);
        appMapper.insert(app);
        return app;
    }

    public AppInfoEntity update(String appId, AppInfoEntity updates) {
        findByAppId(appId);
        updates.setUpdateTime(LocalDateTime.now());
        appMapper.update(updates, new LambdaQueryWrapper<AppInfoEntity>().eq(AppInfoEntity::getAppId, appId));
        return findByAppId(appId);
    }

    public void enable(String appId) {
        findByAppId(appId);
        appMapper.update(null, new LambdaUpdateWrapper<AppInfoEntity>()
                .set(AppInfoEntity::getEnabled, true)
                .set(AppInfoEntity::getUpdateTime, LocalDateTime.now())
                .eq(AppInfoEntity::getAppId, appId));
    }

    public void disable(String appId) {
        findByAppId(appId);
        appMapper.update(null, new LambdaUpdateWrapper<AppInfoEntity>()
                .set(AppInfoEntity::getEnabled, false)
                .set(AppInfoEntity::getUpdateTime, LocalDateTime.now())
                .eq(AppInfoEntity::getAppId, appId));
    }

    public void delete(String appId) {
        findByAppId(appId);
        accessRuleMapper.delete(new LambdaQueryWrapper<AccessRuleEntity>().eq(AccessRuleEntity::getAppId, appId));
        authMapper.delete(new LambdaQueryWrapper<AppModelAuthEntity>().eq(AppModelAuthEntity::getAppId, appId));
        credentialMapper.delete(new LambdaQueryWrapper<AppCredentialEntity>().eq(AppCredentialEntity::getAppId, appId));
        appMapper.delete(new LambdaQueryWrapper<AppInfoEntity>().eq(AppInfoEntity::getAppId, appId));
    }
}
