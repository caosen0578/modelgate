package com.pab.ficc.ibp.modelgate.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.pab.ficc.ibp.modelgate.server.cache.GatewayCacheManager;
import com.pab.ficc.ibp.modelgate.server.common.BusinessException;
import com.pab.ficc.ibp.modelgate.server.common.ErrorCode;
import com.pab.ficc.ibp.modelgate.server.domain.entity.AppInfoEntity;
import com.pab.ficc.ibp.modelgate.server.domain.entity.AppServiceAuthEntity;
import com.pab.ficc.ibp.modelgate.server.domain.entity.ServiceEntity;
import com.pab.ficc.ibp.modelgate.server.domain.vo.ServiceAuthVO;
import com.pab.ficc.ibp.modelgate.server.domain.vo.ServiceAuthorizedAppVO;
import com.pab.ficc.ibp.modelgate.server.mapper.AppMapper;
import com.pab.ficc.ibp.modelgate.server.mapper.AppServiceAuthMapper;
import com.pab.ficc.ibp.modelgate.server.mapper.ServiceMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 接入系统 × 通用服务 的授权管理（{@code mg_app_service_auth}）。
 *
 * <p>与 {@code ModelAuthService} 平级、互不影响：<b>凭证鉴权完全共用</b>
 * （同一个 appId 的同一把 key 既能调模型也能调服务），
 * 真正决定"这个系统能不能调这个服务"的是本表。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ServiceAuthService {

    private final AppServiceAuthMapper authMapper;
    private final AppMapper appMapper;
    private final ServiceMapper serviceMapper;
    private final GatewayCacheManager cacheManager;

    // ===================== 查询 =====================

    public List<AppServiceAuthEntity> listByAppId(String appId) {
        return authMapper.selectList(new LambdaQueryWrapper<AppServiceAuthEntity>()
                .eq(AppServiceAuthEntity::getAppId, appId)
                .orderByDesc(AppServiceAuthEntity::getCreateTime));
    }

    /** 转发热路径用：走 Caffeine L1（60s）。 */
    public List<AppServiceAuthEntity> listByAppIdCached(String appId) {
        return cacheManager.getServiceAuths(appId, () -> listByAppId(appId));
    }

    /**
     * 某系统的服务授权列表，联查回填服务名称与对外前缀。
     *
     * <p>两次查询而非逐条查服务：先取授权，再按 serviceId 批量捞服务，避免 N+1。
     * 与 {@code ModelAuthService.listByAppIdWithModelName} 同一手法。
     */
    public List<ServiceAuthVO> listByAppIdWithServiceName(String appId) {
        List<AppServiceAuthEntity> auths = listByAppId(appId);
        if (auths.isEmpty()) {
            return List.of();
        }
        Set<String> serviceIds = auths.stream()
                .map(AppServiceAuthEntity::getServiceId).collect(Collectors.toSet());
        Map<String, ServiceEntity> svcMap = serviceMapper.selectList(
                        new LambdaQueryWrapper<ServiceEntity>().in(ServiceEntity::getServiceId, serviceIds))
                .stream().collect(Collectors.toMap(ServiceEntity::getServiceId, s -> s, (a, b) -> a));

        return auths.stream().map(a -> ServiceAuthVO.of(a, svcMap.get(a.getServiceId())))
                .collect(Collectors.toList());
    }

    /** 反查：某个服务被哪些接入系统授权，联出系统名称与负责人。同样两次查询避免 N+1。 */
    public List<ServiceAuthorizedAppVO> listAuthorizedApps(String serviceId) {
        List<AppServiceAuthEntity> auths = authMapper.selectList(
                new LambdaQueryWrapper<AppServiceAuthEntity>()
                        .eq(AppServiceAuthEntity::getServiceId, serviceId)
                        .orderByDesc(AppServiceAuthEntity::getCreateTime));
        if (auths.isEmpty()) {
            return List.of();
        }
        Set<String> appIds = auths.stream().map(AppServiceAuthEntity::getAppId).collect(Collectors.toSet());
        Map<String, AppInfoEntity> appMap = appMapper.selectList(
                        new LambdaQueryWrapper<AppInfoEntity>().in(AppInfoEntity::getAppId, appIds))
                .stream().collect(Collectors.toMap(AppInfoEntity::getAppId, a -> a, (a, b) -> a));

        return auths.stream().map(a -> ServiceAuthorizedAppVO.of(a, appMap.get(a.getAppId())))
                .collect(Collectors.toList());
    }

    /** 全部服务的授权系统数量，供服务列表展示"被几个系统授权"。一次查询后内存聚合，避免逐行 N+1。 */
    public Map<String, Long> countByServiceId() {
        return authMapper.selectList(null).stream()
                .collect(Collectors.groupingBy(AppServiceAuthEntity::getServiceId, Collectors.counting()));
    }

    /** 全部接入系统的已授权服务数量，供接入系统列表展示。 */
    public Map<String, Long> countByAppId() {
        return authMapper.selectList(null).stream()
                .collect(Collectors.groupingBy(AppServiceAuthEntity::getAppId, Collectors.counting()));
    }

    // ===================== 写入 =====================

    /**
     * 批量授权：一个接入系统 × 多个服务，共用同一套配额模板。
     *
     * <p>已存在的授权行<b>跳过而不是覆盖</b>：运营在页面上勾选时很容易把已授权的服务
     * 一起勾上，覆盖会把人家单独调过的 QPS 重置成模板值。要改配额请走 update。
     */
    @Transactional(transactionManager = "tdsqlTransactionManager", rollbackFor = Exception.class)
    public void addAuthorizations(String appId, List<String> serviceIds, AppServiceAuthEntity template) {
        if (serviceIds == null || serviceIds.isEmpty()) {
            return;
        }
        // 校验接入系统与服务都存在，避免写入指向不存在对象的授权行
        if (!appMapper.exists(new LambdaQueryWrapper<AppInfoEntity>().eq(AppInfoEntity::getAppId, appId))) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "appId: " + appId);
        }
        Set<String> existingServices = serviceMapper.selectList(
                        new LambdaQueryWrapper<ServiceEntity>().in(ServiceEntity::getServiceId, serviceIds))
                .stream().map(ServiceEntity::getServiceId).collect(Collectors.toSet());

        Set<String> alreadyAuthorized = listByAppId(appId).stream()
                .map(AppServiceAuthEntity::getServiceId).collect(Collectors.toSet());

        int inserted = 0;
        for (String serviceId : serviceIds) {
            if (!existingServices.contains(serviceId)) {
                throw new BusinessException(ErrorCode.SERVICE_NOT_FOUND, "serviceId: " + serviceId);
            }
            if (alreadyAuthorized.contains(serviceId)) {
                log.info("[ServiceAuth] 已授权，跳过: appId={}, serviceId={}", appId, serviceId);
                continue;
            }
            AppServiceAuthEntity e = new AppServiceAuthEntity();
            e.setAppId(appId);
            e.setServiceId(serviceId);
            e.setEnabled(template.getEnabled() == null ? Boolean.TRUE : template.getEnabled());
            e.setQpsLimit(template.getQpsLimit() == null ? 0 : template.getQpsLimit());
            e.setDailyLimit(template.getDailyLimit() == null ? 0 : template.getDailyLimit());
            e.setExpiresAt(template.getExpiresAt());
            e.setCreateTime(LocalDateTime.now());
            e.setUpdateTime(LocalDateTime.now());
            authMapper.insert(e);
            inserted++;
        }
        log.info("[ServiceAuth] 批量授权完成: appId={}, 请求={}, 新增={}", appId, serviceIds.size(), inserted);
        cacheManager.evictServiceAuthCache(appId);
    }

    /**
     * 修改单条授权。expiresAt 传 null 的语义是「改为永久有效」，不是「保持原值」。
     *
     * <p>返回更新后的 VO 而不是 void，与 {@code ModelAuthService.updateAuthorization} 一致：
     * 前端保存后要立刻回显新值，返回 VO 省掉一次 list 往返。
     */
    public ServiceAuthVO updateAuthorization(String appId, String serviceId, Boolean enabled,
                                             Integer qpsLimit, Integer dailyLimit, LocalDateTime expiresAt) {
        AppServiceAuthEntity existing = authMapper.selectOne(new LambdaQueryWrapper<AppServiceAuthEntity>()
                .eq(AppServiceAuthEntity::getAppId, appId)
                .eq(AppServiceAuthEntity::getServiceId, serviceId));
        if (existing == null) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,
                    "授权不存在: appId=" + appId + ", serviceId=" + serviceId);
        }
        LambdaUpdateWrapper<AppServiceAuthEntity> w = new LambdaUpdateWrapper<AppServiceAuthEntity>()
                .eq(AppServiceAuthEntity::getAppId, appId)
                .eq(AppServiceAuthEntity::getServiceId, serviceId);
        if (enabled != null)    w.set(AppServiceAuthEntity::getEnabled, enabled);
        if (qpsLimit != null)   w.set(AppServiceAuthEntity::getQpsLimit, qpsLimit);
        if (dailyLimit != null) w.set(AppServiceAuthEntity::getDailyLimit, dailyLimit);
        // expiresAt 无条件 set：null 就是"改为永久"，页面上清空日期框就是这个意思
        w.set(AppServiceAuthEntity::getExpiresAt, expiresAt);
        w.set(AppServiceAuthEntity::getUpdateTime, LocalDateTime.now());
        authMapper.update(null, w);

        log.info("[ServiceAuth] 授权已更新: appId={}, serviceId={}, enabled={}, qps={}, daily={}, expiresAt={}",
                appId, serviceId, enabled, qpsLimit, dailyLimit, expiresAt);
        cacheManager.evictServiceAuthCache(appId);

        AppServiceAuthEntity updated = authMapper.selectOne(new LambdaQueryWrapper<AppServiceAuthEntity>()
                .eq(AppServiceAuthEntity::getAppId, appId)
                .eq(AppServiceAuthEntity::getServiceId, serviceId));
        return ServiceAuthVO.of(updated, serviceMapper.selectOne(
                new LambdaQueryWrapper<ServiceEntity>().eq(ServiceEntity::getServiceId, serviceId)));
    }

    /**
     * 启用 / 禁用单条授权。
     *
     * <p>与 {@code updateAuthorization} 分开：列表上的启停是一次点击就该完成的动作，
     * 走 update 的话前端必须先把 qps/daily/expiresAt 全带上，
     * 少带一个就会把人家配好的配额顺手清掉——尤其 expiresAt 传 null 的语义是"改为永久"。
     * 模型侧同样是独立的 PATCH enable/disable，这里保持一致。
     */
    public void setAuthEnabled(String appId, String serviceId, boolean enabled) {
        AppServiceAuthEntity existing = authMapper.selectOne(new LambdaQueryWrapper<AppServiceAuthEntity>()
                .eq(AppServiceAuthEntity::getAppId, appId)
                .eq(AppServiceAuthEntity::getServiceId, serviceId));
        if (existing == null) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,
                    "授权不存在: appId=" + appId + ", serviceId=" + serviceId);
        }
        authMapper.update(null, new LambdaUpdateWrapper<AppServiceAuthEntity>()
                .eq(AppServiceAuthEntity::getAppId, appId)
                .eq(AppServiceAuthEntity::getServiceId, serviceId)
                .set(AppServiceAuthEntity::getEnabled, enabled)
                .set(AppServiceAuthEntity::getUpdateTime, LocalDateTime.now()));
        log.info("[ServiceAuth] 授权已{}: appId={}, serviceId={}", enabled ? "启用" : "禁用", appId, serviceId);
        cacheManager.evictServiceAuthCache(appId);
    }

    /** 撤销授权（逻辑删除）。唯一键含 is_del，撤销后可以重新授权。 */
    public void revoke(String appId, String serviceId) {
        int rows = authMapper.delete(new LambdaQueryWrapper<AppServiceAuthEntity>()
                .eq(AppServiceAuthEntity::getAppId, appId)
                .eq(AppServiceAuthEntity::getServiceId, serviceId));
        log.info("[ServiceAuth] 授权已撤销: appId={}, serviceId={}, rows={}", appId, serviceId, rows);
        cacheManager.evictServiceAuthCache(appId);
    }
}
