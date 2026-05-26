package com.pab.ficc.ibp.modelgate.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.pab.ficc.ibp.modelgate.server.cache.GatewayCacheManager;
import com.pab.ficc.ibp.modelgate.server.common.BusinessException;
import com.pab.ficc.ibp.modelgate.server.common.ErrorCode;
import com.pab.ficc.ibp.modelgate.server.domain.entity.AccessRule;
import com.pab.ficc.ibp.modelgate.server.mapper.AccessRuleMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class AccessRuleService {

    private final AccessRuleMapper accessRuleMapper;
    private final GatewayCacheManager cacheManager;

    public List<AccessRule> listByAppId(String appId) {
        return accessRuleMapper.selectList(
                new LambdaQueryWrapper<AccessRule>().eq(AccessRule::getAppId, appId)
                        .orderByDesc(AccessRule::getCreatedAt));
    }

    public List<AccessRule> listByAppIdCached(String appId) {
        return cacheManager.getAccessRules(appId, () -> listByAppId(appId));
    }

    public AccessRule add(String appId, AccessRule rule) {
        rule.setAppId(appId);
        rule.setCreatedAt(LocalDateTime.now());
        accessRuleMapper.insert(rule);
        cacheManager.evictAccessRuleCache(appId);
        return rule;
    }

    public void delete(String appId, Long ruleId) {
        AccessRule rule = Optional.ofNullable(accessRuleMapper.selectById(ruleId))
                .filter(r -> r.getAppId().equals(appId))
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "ruleId: " + ruleId));
        accessRuleMapper.deleteById(ruleId);
        cacheManager.evictAccessRuleCache(appId);
    }
}
