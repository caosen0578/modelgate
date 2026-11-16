package com.pab.ficc.ibp.modelgate.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.pab.ficc.ibp.modelgate.server.common.BusinessException;
import com.pab.ficc.ibp.modelgate.server.common.ErrorCode;
import com.pab.ficc.ibp.modelgate.server.domain.bo.AuditQueryBO;
import com.pab.ficc.ibp.modelgate.server.domain.entity.AuditLogEntity;
import com.pab.ficc.ibp.modelgate.server.mapper.AuditLogMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
@RequiredArgsConstructor
public class AuditQueryService {
    private final AppScopeResolver appScopeResolver;

    public Optional<LambdaQueryWrapper<AuditLogEntity>> applicationFilters(AuditQueryBO query) {
        var scope = appScopeResolver.resolve(query.getAppId(), query.getAppCode());
        if (scope.matchNothing()) return Optional.empty();
        return Optional.of(commonFilters(query)
                .apply(AuditLogMapper.SYSTEM_AUDIT_SCOPE)
                .eq(scope.eqAppId() != null, AuditLogEntity::getAppId, scope.eqAppId())
                .in(scope.inAppIds() != null, AuditLogEntity::getAppId, scope.inAppIds())
                .eq(hasText(query.getTrafficType()), AuditLogEntity::getTrafficType, query.getTrafficType())
                .eq(hasText(query.getServiceId()), AuditLogEntity::getServiceId, query.getServiceId()));
    }

    public LambdaQueryWrapper<AuditLogEntity> personalFilters(AuditQueryBO query, String personalAppId) {
        return commonFilters(query).eq(AuditLogEntity::getAppId, personalAppId)
                .eq(AuditLogEntity::getTrafficType, "model");
    }

    private LambdaQueryWrapper<AuditLogEntity> commonFilters(AuditQueryBO q) {
        if (q.getStartTime() != null && q.getEndTime() != null && q.getStartTime().isAfter(q.getEndTime())) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "开始时间必须早于或等于结束时间");
        }
        return new LambdaQueryWrapper<AuditLogEntity>()
                .eq(hasText(q.getModelId()), AuditLogEntity::getModelId, q.getModelId())
                .eq(q.getStatus() != null, AuditLogEntity::getHttpStatus, q.getStatus())
                .eq(hasText(q.getTraceId()), AuditLogEntity::getTraceId, q.getTraceId())
                .ge(q.getStartTime() != null, AuditLogEntity::getCreateTime, q.getStartTime())
                .le(q.getEndTime() != null, AuditLogEntity::getCreateTime, q.getEndTime());
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
