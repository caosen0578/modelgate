package com.pab.ficc.ibp.modelgate.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.pab.ficc.ibp.domain.base.vo.PageVo;
import com.pab.ficc.ibp.modelgate.server.admin.PersonalSourcePolicy;
import com.pab.ficc.ibp.modelgate.server.common.AppSubject;
import com.pab.ficc.ibp.modelgate.server.common.BusinessException;
import com.pab.ficc.ibp.modelgate.server.common.ErrorCode;
import com.pab.ficc.ibp.modelgate.server.domain.bo.AppQueryBO;
import com.pab.ficc.ibp.modelgate.server.domain.bo.ModelQueryBO;
import com.pab.ficc.ibp.modelgate.server.domain.bo.PersonalAccessRequests;
import com.pab.ficc.ibp.modelgate.server.domain.entity.AccessRuleEntity;
import com.pab.ficc.ibp.modelgate.server.domain.entity.AppInfoEntity;
import com.pab.ficc.ibp.modelgate.server.domain.entity.AppModelAuthEntity;
import com.pab.ficc.ibp.modelgate.server.domain.entity.ModelInstanceEntity;
import com.pab.ficc.ibp.modelgate.server.domain.vo.AppCredentialVO;
import com.pab.ficc.ibp.modelgate.server.domain.vo.PersonalAccessViews;
import com.pab.ficc.ibp.modelgate.server.mapper.AppMapper;
import com.pab.ficc.ibp.modelgate.server.mapper.AuditLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class PersonalAccessService {
    private static final int MAX_USAGE_DAYS = 90;
    private static final int DEFAULT_USAGE_DAYS = 30;
    private static final int DEFAULT_KEY_DAYS = 90;
    private static final int APP_ID_ATTEMPTS = 4;

    private final AppMapper appMapper;
    private final AuditLogMapper auditLogMapper;
    private final AppService appService;
    private final CredentialService credentialService;
    private final ModelService modelService;
    private final ModelAuthService modelAuthService;
    private final AccessRuleService accessRuleService;

    @Value(AppSubject.PERSONAL_ENABLED_PROPERTY)
    private boolean enabled;

    public PersonalAccessViews.Meta meta() {
        return new PersonalAccessViews.Meta(enabled);
    }

    public PageVo<PersonalAccessViews.Profile> page(AppQueryBO query) {
        String keyword = query.getKeyword() == null ? "" : query.getKeyword().strip();
        IPage<AppInfoEntity> page = appMapper.selectPage(new Page<>(query.getPageIndex(), query.getPageSize()),
                new LambdaQueryWrapper<AppInfoEntity>()
                        .eq(AppInfoEntity::getSubjectType, AppSubject.PERSONAL)
                        .and(!keyword.isEmpty(), w -> w.like(AppInfoEntity::getPersonUm, keyword)
                                .or().like(AppInfoEntity::getAppName, keyword))
                        .eq(query.getEnabled() != null, AppInfoEntity::getEnabled, query.getEnabled())
                        .orderByDesc(AppInfoEntity::getId));
        List<String> ids = page.getRecords().stream().map(AppInfoEntity::getAppId).toList();
        Map<String, Long> authCounts = modelAuthService.countByAppId(ids);
        Map<String, Long> credCounts = credentialService.countByAppId(ids);
        return PageVo.of(page, app -> PersonalAccessViews.Profile.of(app, authCounts.getOrDefault(app.getAppId(), 0L),
                credCounts.getOrDefault(app.getAppId(), 0L)));
    }

    public PersonalAccessViews.Profile detail(String um) {
        return PersonalAccessViews.Profile.of(findPerson(um));
    }

    public PersonalAccessViews.Profile create(PersonalAccessRequests.Profile request) {
        requireEnabled();
        String um = AppSubject.normalizeUm(request.um());
        if (findByUm(um).isPresent()) throw duplicateUm(um);
        for (int attempt = 0; attempt < APP_ID_ATTEMPTS; attempt++) {
            String id = AppSubject.newPersonalAppId();
            // 检查全部历史主体（含软删），避免复用旧审计/配额归属。
            if (appMapper.countHistoricalAppId(id) != 0) continue;
            AppInfoEntity app = new AppInfoEntity();
            app.setAppId(id);
            app.setSubjectType(AppSubject.PERSONAL);
            app.setPersonUm(um);
            app.setSystemName("个人接入");
            app.setAppName(request.name().strip());
            app.setSysCode(AppSubject.PERSONAL);
            app.setAppCode(id);
            app.setOwner(app.getAppName());
            app.setRemark(request.remark());
            app.setEnabled(true);
            try {
                appMapper.insert(app);
            } catch (DuplicateKeyException e) {
                // 并发录入同一 UM 或极小概率编号冲突，由唯一键仲裁。
                if (findByUm(um).isPresent()) throw duplicateUm(um);
                if (appMapper.countHistoricalAppId(id) != 0) continue;
                throw e;
            }
            log.info("[PersonalAccess] 开通: appId={}", id);
            return PersonalAccessViews.Profile.of(app);
        }
        throw new BusinessException(ErrorCode.INTERNAL_ERROR, "个人接入编号生成失败，请重试");
    }

    public PersonalAccessViews.Profile update(PersonalAccessRequests.Profile request) {
        AppInfoEntity app = findPerson(request.um());
        String name = request.name().strip();
        appMapper.update(null, new LambdaUpdateWrapper<AppInfoEntity>()
                .set(AppInfoEntity::getAppName, name)
                .set(AppInfoEntity::getOwner, name)
                .set(AppInfoEntity::getRemark, request.remark())
                .set(AppInfoEntity::getUpdateTime, LocalDateTime.now())
                .eq(AppInfoEntity::getId, app.getId())
                .eq(AppInfoEntity::getSubjectType, AppSubject.PERSONAL));
        log.info("[PersonalAccess] 更新资料: appId={}", app.getAppId());
        return detail(app.getPersonUm());
    }

    public PersonalAccessViews.BatchResult importUsers(PersonalAccessRequests.ImportUsers request) {
        requireEnabled();
        var results = new ArrayList<PersonalAccessViews.BatchItem>();
        var seen = new HashSet<String>();
        for (var user : request.users()) {
            String um = user.um();
            try {
                um = AppSubject.normalizeUm(um);
                if (!seen.add(um)) {
                    results.add(new PersonalAccessViews.BatchItem(um, null, "SKIPPED", "文件中 UM 重复"));
                    continue;
                }
                create(user);
                results.add(new PersonalAccessViews.BatchItem(um, null, "ADDED", "已新增"));
            } catch (BusinessException e) {
                String status = e.getErrorCode() == ErrorCode.RESOURCE_ALREADY_EXISTS ? "SKIPPED" : "FAILED";
                results.add(new PersonalAccessViews.BatchItem(um, null, status, e.getMessage()));
            } catch (RuntimeException e) {
                log.warn("[PersonalAccess] 导入失败: um={}", um, e);
                results.add(new PersonalAccessViews.BatchItem(um, null, "FAILED", "写入失败，请核对后重试"));
            }
        }
        return PersonalAccessViews.BatchResult.of(results);
    }

    public void toggle(String um, boolean active) {
        if (active) requireEnabled();
        String appId = findPerson(um).getAppId();
        if (active) appService.enable(appId);
        else appService.disable(appId);
    }

    public List<AppCredentialVO> credentials(String um) {
        return credentialService.listByAppId(findPerson(um).getAppId()).stream()
                .map(AppCredentialVO::of).toList();
    }

    public String revealKey(String um, Long id) {
        requireEnabled();
        AppInfoEntity app = findPerson(um);
        if (!Boolean.TRUE.equals(app.getEnabled())) throw new BusinessException(ErrorCode.APP_DISABLED);
        return credentialService.revealPersonalApiKey(app.getAppId(), id);
    }

    public PersonalAccessViews.IssuedKey issue(PersonalAccessRequests.Credential request) {
        requireEnabled();
        AppInfoEntity app = findPerson(request.um());
        if (!Boolean.TRUE.equals(app.getEnabled())) throw new BusinessException(ErrorCode.APP_DISABLED);
        LocalDateTime expiresAt = request.expiresAt() == null
                ? LocalDateTime.now().plusDays(DEFAULT_KEY_DAYS) : request.expiresAt();
        requireFuture(expiresAt);
        var created = credentialService.createPersonalApiKey(app.getAppId(), request.credName().strip(), expiresAt);
        return new PersonalAccessViews.IssuedKey(created.credential().getId(), created.plainSecret(), expiresAt);
    }

    public void revoke(String um, Long id) {
        // 禁用而非删除，保留签发、撤销的元数据；个人接口不提供重新启用已撤销 Key。
        credentialService.disable(findPerson(um).getAppId(), id);
    }

    public void deleteCredential(String um, Long id) {
        credentialService.deleteRevokedPersonalKey(findPerson(um).getAppId(), id);
    }

    public IPage<ModelInstanceEntity> models(ModelQueryBO query) {
        query.setEnabled(true);
        return modelService.page(query);
    }

    public List<PersonalAccessViews.Grant> grants(String um) {
        return modelAuthService.listByAppIdWithModelName(findPerson(um).getAppId()).stream()
                .map(PersonalAccessViews.Grant::of).toList();
    }

    public void saveGrant(PersonalAccessRequests.ModelGrant request) {
        requireEnabled();
        String appId = findPerson(request.um()).getAppId();
        String modelId = request.modelId();
        requireActiveModel(modelId);
        if (request.expiresAt() != null) requireFuture(request.expiresAt());
        var values = grantValues(request.qpsLimit(), request.dailyLimit(), request.monthlyTokens(), request.expiresAt());
        var existing = modelAuthService.listByAppId(appId).stream().filter(a -> modelId.equals(a.getModelId())).findFirst();
        values.setPayloadLogEnabled(request.payloadLogEnabled() != null ? request.payloadLogEnabled()
                : existing.map(AppModelAuthEntity::getPayloadLogEnabled).orElse(false));
        if (existing.isPresent()) {
            // 修改配额不会隐式启用一条已停用的授权。
            modelAuthService.update(appId, modelId, values);
        } else {
            modelAuthService.addAuthorizations(appId, List.of(modelId), values);
        }
    }

    public PersonalAccessViews.BatchResult batchGrant(PersonalAccessRequests.BatchGrant request) {
        requireEnabled();
        var ums = request.ums().stream().map(AppSubject::normalizeUm).distinct().toList();
        var modelIds = request.modelIds().stream().distinct().toList();
        if (ums.size() * modelIds.size() > 100) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "每次最多提交 100 个用户与模型的组合");
        }
        if (request.expiresAt() != null) requireFuture(request.expiresAt());
        // 参数校验在写入前完成，避免提交了不存在或停用模型的请求时出现部分授权。
        modelIds.forEach(this::requireActiveModel);
        var values = grantValues(request.qpsLimit(), request.dailyLimit(), request.monthlyTokens(), request.expiresAt());
        var results = new ArrayList<PersonalAccessViews.BatchItem>();
        for (String um : ums) {
            var app = findByUm(um).orElse(null);
            for (String modelId : modelIds) {
                if (app == null || !Boolean.TRUE.equals(app.getEnabled())) {
                    results.add(new PersonalAccessViews.BatchItem(um, modelId, "SKIPPED", "用户不存在或已停用"));
                    continue;
                }
                try {
                    boolean added = modelAuthService.addAuthorizations(app.getAppId(), List.of(modelId), values) == 1;
                    results.add(new PersonalAccessViews.BatchItem(um, modelId, added ? "ADDED" : "SKIPPED",
                            added ? "已授权" : "已有授权，保留原配置"));
                } catch (DuplicateKeyException e) {
                    results.add(new PersonalAccessViews.BatchItem(um, modelId, "SKIPPED", "已有授权，保留原配置"));
                } catch (RuntimeException e) {
                    log.warn("[PersonalAccess] 批量授权写入失败: appId={}, modelId={}", app.getAppId(), modelId, e);
                    results.add(new PersonalAccessViews.BatchItem(um, modelId, "FAILED", "写入失败，请核对后重试"));
                }
            }
        }
        return PersonalAccessViews.BatchResult.of(results);
    }

    private static AppModelAuthEntity grantValues(Integer qps, Integer daily, Long monthly, LocalDateTime expiresAt) {
        var values = new AppModelAuthEntity();
        values.setQpsLimit(qps == null ? 2 : qps);
        values.setDailyLimit(daily == null ? 200 : daily);
        values.setMonthlyTokens(monthly == null ? 100_000L : monthly);
        values.setExpiresAt(expiresAt);
        values.setPayloadLogEnabled(false);
        return values;
    }

    public void toggleGrant(String um, String modelId, boolean active) {
        if (active) {
            requireEnabled();
            requireActiveModel(modelId);
        }
        String appId = findPerson(um).getAppId();
        if (active) modelAuthService.enable(appId, modelId);
        else modelAuthService.disable(appId, modelId);
    }

    public List<PersonalAccessViews.SourceRule> sourceRules(String um) {
        return accessRuleService.listByAppId(findPerson(um).getAppId()).stream()
                .map(PersonalAccessViews.SourceRule::of).toList();
    }

    public PersonalAccessViews.SourceRule addSourceRule(PersonalAccessRequests.SourceRule request) {
        requireEnabled();
        String appId = findPerson(request.um()).getAppId();
        var rule = new AccessRuleEntity();
        rule.setRuleType(request.ruleType());
        try {
            rule.setRuleValue(PersonalSourcePolicy.normalizeRule(request.ruleType(), request.ruleValue()));
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, e.getMessage());
        }
        rule.setRemark(request.remark());
        return PersonalAccessViews.SourceRule.of(accessRuleService.add(appId, rule, true));
    }

    public void deleteSourceRule(String um, Long id) {
        requireEnabled();
        accessRuleService.delete(findPerson(um).getAppId(), id, true);
    }

    public PersonalAccessViews.Usage usage(String um, LocalDate start, LocalDate end) {
        AppInfoEntity app = findPerson(um);
        LocalDate to = end == null ? LocalDate.now() : end;
        LocalDate from = start == null ? to.minusDays(DEFAULT_USAGE_DAYS - 1L) : start;
        long days = ChronoUnit.DAYS.between(from, to);
        if (days < 0 || days >= MAX_USAGE_DAYS || to.isAfter(LocalDate.now()) || from.getYear() < 1000) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "请选择不超过 90 天的历史日期区间");
        }
        List<PersonalAccessViews.ModelUsage> models = auditLogMapper
                .selectPersonalUsageByModel(app.getAppId(), from.atStartOfDay(), to.plusDays(1).atStartOfDay())
                .stream().map(row -> new PersonalAccessViews.ModelUsage((String) row.get("model_id"),
                        PersonalAccessViews.Totals.of(number(row, "total_requests"), number(row, "input_tokens"),
                                number(row, "output_tokens"), number(row, "upstream_tokens"),
                                number(row, "estimated_tokens"))))
                .toList();
        PersonalAccessViews.Totals totals = models.stream().map(PersonalAccessViews.ModelUsage::totals)
                .reduce(PersonalAccessViews.Totals.of(0, 0, 0, 0, 0), PersonalAccessViews.Totals::plus);
        return new PersonalAccessViews.Usage(app.getPersonUm(), app.getAppName(), from, to, totals, models);
    }

    private AppInfoEntity findPerson(String um) {
        String normalized = AppSubject.normalizeUm(um);
        return findByUm(normalized).orElseThrow(() ->
                new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "未开通个人接入: " + normalized));
    }

    private Optional<AppInfoEntity> findByUm(String normalizedUm) {
        return Optional.ofNullable(appMapper.selectOne(new LambdaQueryWrapper<AppInfoEntity>()
                .eq(AppInfoEntity::getSubjectType, AppSubject.PERSONAL)
                .eq(AppInfoEntity::getPersonUm, normalizedUm)));
    }

    private void requireEnabled() {
        if (!enabled) throw new BusinessException(ErrorCode.PERSONAL_ACCESS_DISABLED);
    }

    private void requireActiveModel(String modelId) {
        if (!Boolean.TRUE.equals(modelService.findByModelId(modelId).getEnabled())) {
            throw new BusinessException(ErrorCode.MODEL_DISABLED);
        }
    }

    private static void requireFuture(LocalDateTime value) {
        if (!value.isAfter(LocalDateTime.now())) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "有效期必须晚于当前时间");
        }
    }

    private static BusinessException duplicateUm(String um) {
        return new BusinessException(ErrorCode.RESOURCE_ALREADY_EXISTS, "UM 已开通个人接入，请查看原账户: " + um);
    }

    private static long number(Map<String, Object> row, String key) {
        return row.get(key) instanceof Number n ? n.longValue() : 0L;
    }
}
