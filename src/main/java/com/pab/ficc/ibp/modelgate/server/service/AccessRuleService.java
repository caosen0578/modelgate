package com.pab.ficc.ibp.modelgate.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.pab.ficc.ibp.modelgate.server.cache.GatewayCacheManager;
import com.pab.ficc.ibp.modelgate.server.common.BusinessException;
import com.pab.ficc.ibp.modelgate.server.common.ErrorCode;
import com.pab.ficc.ibp.modelgate.server.domain.entity.AccessRuleEntity;
import com.pab.ficc.ibp.modelgate.server.mapper.AccessRuleMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class AccessRuleService {

    private final AccessRuleMapper accessRuleMapper;
    private final GatewayCacheManager cacheManager;

    public List<AccessRuleEntity> listByAppId(String appId) {
        return accessRuleMapper.selectList(
                new LambdaQueryWrapper<AccessRuleEntity>().eq(AccessRuleEntity::getAppId, appId)
                        .orderByDesc(AccessRuleEntity::getCreateTime));
    }

    public List<AccessRuleEntity> listByAppIdCached(String appId) {
        return cacheManager.getAccessRules(appId, () -> listByAppId(appId));
    }

    public AccessRuleEntity add(String appId, AccessRuleEntity rule) {
        rule.setAppId(appId);
        // create_time 等基础字段由 AuditFieldMetaObjectHandler 自动填充，不得手动 set（05 §10）
        accessRuleMapper.insert(rule);
        cacheManager.evictAccessRuleCache(appId);
        // 来源白名单属安全配置，增删必须留痕：规则为空 = 不限制来源，
        // 误删最后一条会让该系统对全网开放，事后要能查到是谁在什么时候删的
        log.info("[AccessRule] 新增访问规则: appId={}, ruleId={}, type={}, value={}",
                appId, rule.getId(), rule.getRuleType(), rule.getRuleValue());
        return rule;
    }

    public void delete(String appId, Long ruleId) {
        AccessRuleEntity rule = Optional.ofNullable(accessRuleMapper.selectById(ruleId))
                .filter(r -> r.getAppId().equals(appId))
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "ruleId: " + ruleId));
        accessRuleMapper.deleteById(ruleId);
        cacheManager.evictAccessRuleCache(appId);
        long remaining = accessRuleMapper.selectCount(
                new LambdaQueryWrapper<AccessRuleEntity>().eq(AccessRuleEntity::getAppId, appId));
        log.info("[AccessRule] 删除访问规则: appId={}, ruleId={}, type={}, value={}, 剩余规则={} 条",
                appId, ruleId, rule.getRuleType(), rule.getRuleValue(), remaining);
        if (remaining == 0) {
            // 08 §业务规则：规则为空 = 不限制来源。删到 0 条是语义翻转（白名单 → 全放开），
            // 不是普通的删除操作，必须 WARN 提醒
            log.warn("[AccessRule] appId={} 已无任何访问规则，来源限制失效，该系统将接受任意来源的调用", appId);
        }
    }
}
