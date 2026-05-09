package com.pab.ficc.idp.modelgate.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.pab.ficc.idp.modelgate.common.BusinessException;
import com.pab.ficc.idp.modelgate.common.ErrorCode;
import com.pab.ficc.idp.modelgate.entity.AccessRule;
import com.pab.ficc.idp.modelgate.entity.AppCredential;
import com.pab.ficc.idp.modelgate.entity.AppInfo;
import com.pab.ficc.idp.modelgate.entity.AppModelAuth;
import com.pab.ficc.idp.modelgate.mapper.AccessRuleMapper;
import com.pab.ficc.idp.modelgate.mapper.AppCredentialMapper;
import com.pab.ficc.idp.modelgate.mapper.AppMapper;
import com.pab.ficc.idp.modelgate.mapper.AppModelAuthMapper;
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

    public List<AppInfo> findAll() {
        return appMapper.selectList(null);
    }

    public AppInfo findByAppId(String appId) {
        return Optional.ofNullable(appMapper.selectOne(
                new LambdaQueryWrapper<AppInfo>().eq(AppInfo::getAppId, appId)))
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "appId: " + appId));
    }

    public AppInfo create(AppInfo app) {
        boolean exists = appMapper.exists(new LambdaQueryWrapper<AppInfo>().eq(AppInfo::getAppId, app.getAppId()));
        if (exists) {
            throw new BusinessException(ErrorCode.RESOURCE_ALREADY_EXISTS, "appId: " + app.getAppId());
        }
        app.setCreatedAt(LocalDateTime.now());
        app.setUpdatedAt(LocalDateTime.now());
        app.setEnabled(true);
        appMapper.insert(app);
        return app;
    }

    public AppInfo update(String appId, AppInfo updates) {
        findByAppId(appId);
        updates.setUpdatedAt(LocalDateTime.now());
        appMapper.update(updates, new LambdaQueryWrapper<AppInfo>().eq(AppInfo::getAppId, appId));
        return findByAppId(appId);
    }

    public void enable(String appId) {
        findByAppId(appId);
        appMapper.update(null, new LambdaUpdateWrapper<AppInfo>()
                .set(AppInfo::getEnabled, true)
                .set(AppInfo::getUpdatedAt, LocalDateTime.now())
                .eq(AppInfo::getAppId, appId));
    }

    public void disable(String appId) {
        findByAppId(appId);
        appMapper.update(null, new LambdaUpdateWrapper<AppInfo>()
                .set(AppInfo::getEnabled, false)
                .set(AppInfo::getUpdatedAt, LocalDateTime.now())
                .eq(AppInfo::getAppId, appId));
    }

    public void delete(String appId) {
        findByAppId(appId);
        accessRuleMapper.delete(new LambdaQueryWrapper<AccessRule>().eq(AccessRule::getAppId, appId));
        authMapper.delete(new LambdaQueryWrapper<AppModelAuth>().eq(AppModelAuth::getAppId, appId));
        credentialMapper.delete(new LambdaQueryWrapper<AppCredential>().eq(AppCredential::getAppId, appId));
        appMapper.delete(new LambdaQueryWrapper<AppInfo>().eq(AppInfo::getAppId, appId));
    }
}
