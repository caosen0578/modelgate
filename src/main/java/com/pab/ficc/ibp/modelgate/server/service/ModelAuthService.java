package com.pab.ficc.ibp.modelgate.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.pab.ficc.ibp.modelgate.server.cache.GatewayCacheManager;
import com.pab.ficc.ibp.modelgate.server.common.BusinessException;
import com.pab.ficc.ibp.modelgate.server.common.ErrorCode;
import com.pab.ficc.ibp.modelgate.server.domain.entity.AppInfoEntity;
import com.pab.ficc.ibp.modelgate.server.domain.entity.AppModelAuthEntity;
import com.pab.ficc.ibp.modelgate.server.domain.entity.ModelInstanceEntity;
import com.pab.ficc.ibp.modelgate.server.domain.vo.AppModelAuthVO;
import com.pab.ficc.ibp.modelgate.server.domain.vo.ModelAuthorizedAppVO;
import com.pab.ficc.ibp.modelgate.server.mapper.ModelMapper;
import com.pab.ficc.ibp.modelgate.server.mapper.AppMapper;
import com.pab.ficc.ibp.modelgate.server.mapper.AppModelAuthMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ModelAuthService {

    private final AppModelAuthMapper authMapper;
    private final AppMapper appMapper;
    private final ModelMapper modelMapper;
    private final GatewayCacheManager cacheManager;

    public List<AppModelAuthEntity> listByAppId(String appId) {
        return authMapper.selectList(
                new LambdaQueryWrapper<AppModelAuthEntity>().eq(AppModelAuthEntity::getAppId, appId)
                        .orderByDesc(AppModelAuthEntity::getCreateTime));
    }

    public List<AppModelAuthEntity> listByAppIdCached(String appId) {
        return cacheManager.getModelAuths(appId, () -> listByAppId(appId));
    }

    /**
     * 某系统的授权列表，联查回填模型名称与类型。
     *
     * <p>两次查询而非逐条查模型：先取授权，再按 modelId 批量捞模型，避免 N+1。
     * 回填的是整个模型对象而非仅名称——前端「接入示例」还要用 modelType 决定示例路径。
     */
    public List<AppModelAuthVO> listByAppIdWithModelName(String appId) {
        List<AppModelAuthEntity> auths = listByAppId(appId);
        if (auths.isEmpty()) {
            return List.of();
        }
        Set<String> modelIds = auths.stream().map(AppModelAuthEntity::getModelId).collect(Collectors.toSet());
        Map<String, ModelInstanceEntity> modelMap = modelMapper.selectList(
                        new LambdaQueryWrapper<ModelInstanceEntity>().in(ModelInstanceEntity::getModelId, modelIds))
                .stream().collect(Collectors.toMap(ModelInstanceEntity::getModelId, m -> m, (a, b) -> a));

        return auths.stream()
                .map(a -> AppModelAuthVO.of(a, modelMap.get(a.getModelId())))
                .collect(Collectors.toList());
    }

    /**
     * 反查：某个模型被哪些接入系统授权，联出系统名称与负责人。
     *
     * <p>两次查询而非逐条查 App：先取该模型的全部授权，再按 appId 批量捞系统信息，避免 N+1。
     * 授权为空时直接返回，不触发第二次查询。
     */
    public List<ModelAuthorizedAppVO> listAuthorizedApps(String modelId) {
        List<AppModelAuthEntity> auths = authMapper.selectList(
                new LambdaQueryWrapper<AppModelAuthEntity>()
                        .eq(AppModelAuthEntity::getModelId, modelId)
                        .orderByDesc(AppModelAuthEntity::getCreateTime));
        if (auths.isEmpty()) {
            return List.of();
        }

        Set<String> appIds = auths.stream().map(AppModelAuthEntity::getAppId).collect(Collectors.toSet());
        Map<String, AppInfoEntity> appMap = appMapper.selectList(
                        new LambdaQueryWrapper<AppInfoEntity>().in(AppInfoEntity::getAppId, appIds))
                .stream().collect(Collectors.toMap(AppInfoEntity::getAppId, a -> a, (a, b) -> a));

        return auths.stream()
                .map(a -> ModelAuthorizedAppVO.of(a, appMap.get(a.getAppId())))
                .collect(Collectors.toList());
    }

    /**
     * 全部模型的授权系统数量，用于模型列表展示"被几个系统授权"。
     *
     * <p>一次查询后在内存聚合，避免模型列表逐行查询造成 N+1。
     * 统计的是**授权记录数**，不区分是否实际生效——生效与否在详情弹窗里逐条给结论。
     */
    public Map<String, Long> countByModelId() {
        return authMapper.selectList(null).stream()
                .collect(Collectors.groupingBy(AppModelAuthEntity::getModelId, Collectors.counting()));
    }

    /**
     * 全部接入系统的已授权模型数量，用于系统列表展示。
     * 同样一次查询后内存聚合，避免逐行 N+1。
     */
    public Map<String, Long> countByAppId() {
        return authMapper.selectList(null).stream()
                .collect(Collectors.groupingBy(AppModelAuthEntity::getAppId, Collectors.counting()));
    }

    /**
     * 该 (接入系统, 模型) 是否开启了报文记录。
     *
     * <p><b>走缓存</b>（{@link #listByAppIdCached}，L1 60s）：本方法在<b>每个业务请求</b>的
     * doFinally 里被调用，直查 DB 等于给热路径平白加一次查询。
     * 代价是开关变更最长 60s 后生效，对"是否留档报文"这种非实时诉求完全可接受
     * （授权增删改都会主动 evict，正常操作路径下是秒级生效的）。
     */
    public boolean isPayloadLogEnabled(String appId, String modelId) {
        return listByAppIdCached(appId).stream()
                .filter(a -> a.getModelId().equals(modelId))
                .findFirst()
                .map(a -> Boolean.TRUE.equals(a.getPayloadLogEnabled()))
                .orElse(false);
    }

    public void addAuthorizations(String appId, List<String> modelIds, AppModelAuthEntity template) {
        int added = 0;
        int skipped = 0;
        for (String modelId : modelIds) {
            boolean exists = authMapper.exists(new LambdaQueryWrapper<AppModelAuthEntity>()
                    .eq(AppModelAuthEntity::getAppId, appId).eq(AppModelAuthEntity::getModelId, modelId));
            if (exists) {
                // 已存在则静默跳过是有意的（幂等），但必须留痕：
                // 否则"重新授权改配额没生效"这类问题查不到原因——实际是走了跳过分支
                log.info("[ModelAuth] 授权已存在，跳过: appId={}, modelId={}", appId, modelId);
                skipped++;
                continue;
            }

            AppModelAuthEntity auth = new AppModelAuthEntity();
            auth.setAppId(appId);
            auth.setModelId(modelId);
            auth.setEnabled(true);
            auth.setQpsLimit(template.getQpsLimit());
            auth.setDailyLimit(template.getDailyLimit());
            auth.setMonthlyTokens(template.getMonthlyTokens());
            auth.setExpiresAt(template.getExpiresAt());
            auth.setPayloadLogEnabled(Boolean.TRUE.equals(template.getPayloadLogEnabled()));
            auth.setCreateTime(LocalDateTime.now());
            auth.setUpdateTime(LocalDateTime.now());
            authMapper.insert(auth);
            added++;
        }
        cacheManager.evictModelAuthCache(appId);
        log.info("[ModelAuth] 新增授权: appId={}, 请求={} 个, 新增={} 个, 跳过={} 个, qps={}, daily={}, monthlyTokens={}, expiresAt={}",
                appId, modelIds.size(), added, skipped,
                template.getQpsLimit(), template.getDailyLimit(), template.getMonthlyTokens(), template.getExpiresAt());
    }

    /**
     * 更新授权配额与有效期。
     *
     * <p>用 {@code LambdaUpdateWrapper} 显式 set 而非实体更新：MyBatis-Plus 默认 NOT_NULL 策略会
     * **跳过 null 字段**，走实体更新会导致「清空有效期」这个操作无效——用户在页面上删掉日期保存，
     * 提示成功但库里的 {@code expires_at} 纹丝不动。配额三项同理（虽然 BO 里默认 0，防御性一并显式 set）。
     */
    public AppModelAuthEntity update(String appId, String modelId, AppModelAuthEntity updates) {
        getAuthOrThrow(appId, modelId);
        authMapper.update(null, new LambdaUpdateWrapper<AppModelAuthEntity>()
                .set(AppModelAuthEntity::getQpsLimit, updates.getQpsLimit())
                .set(AppModelAuthEntity::getDailyLimit, updates.getDailyLimit())
                .set(AppModelAuthEntity::getMonthlyTokens, updates.getMonthlyTokens())
                .set(AppModelAuthEntity::getExpiresAt, updates.getExpiresAt())
                .set(AppModelAuthEntity::getPayloadLogEnabled, Boolean.TRUE.equals(updates.getPayloadLogEnabled()))
                .set(AppModelAuthEntity::getUpdateTime, LocalDateTime.now())
                .eq(AppModelAuthEntity::getAppId, appId).eq(AppModelAuthEntity::getModelId, modelId));
        cacheManager.evictModelAuthCache(appId);
        log.info("[ModelAuth] 更新授权: appId={}, modelId={}, qps={}, daily={}, monthlyTokens={}, expiresAt={}, 报文记录={}",
                appId, modelId, updates.getQpsLimit(), updates.getDailyLimit(),
                updates.getMonthlyTokens(), updates.getExpiresAt(),
                Boolean.TRUE.equals(updates.getPayloadLogEnabled()) ? "开启" : "关闭");
        return getAuthOrThrow(appId, modelId);
    }

    public void enable(String appId, String modelId) {
        getAuthOrThrow(appId, modelId);
        authMapper.update(null, new LambdaUpdateWrapper<AppModelAuthEntity>()
                .set(AppModelAuthEntity::getEnabled, true)
                .set(AppModelAuthEntity::getUpdateTime, LocalDateTime.now())
                .eq(AppModelAuthEntity::getAppId, appId).eq(AppModelAuthEntity::getModelId, modelId));
        cacheManager.evictModelAuthCache(appId);
        log.info("[ModelAuth] 启用授权: appId={}, modelId={}", appId, modelId);
    }

    public void disable(String appId, String modelId) {
        getAuthOrThrow(appId, modelId);
        authMapper.update(null, new LambdaUpdateWrapper<AppModelAuthEntity>()
                .set(AppModelAuthEntity::getEnabled, false)
                .set(AppModelAuthEntity::getUpdateTime, LocalDateTime.now())
                .eq(AppModelAuthEntity::getAppId, appId).eq(AppModelAuthEntity::getModelId, modelId));
        cacheManager.evictModelAuthCache(appId);
        log.info("[ModelAuth] 禁用授权: appId={}, modelId={}（该系统对此模型的调用将立即 403）", appId, modelId);
    }

    public void revoke(String appId, String modelId) {
        int rows = authMapper.delete(new LambdaQueryWrapper<AppModelAuthEntity>()
                .eq(AppModelAuthEntity::getAppId, appId).eq(AppModelAuthEntity::getModelId, modelId));
        cacheManager.evictModelAuthCache(appId);
        // 打出受影响行数：本方法不做存在性校验，撤销一个不存在的授权同样返回成功，
        // rows=0 是区分"撤销成功"与"本来就没有"的唯一线索
        log.info("[ModelAuth] 撤销授权: appId={}, modelId={}, 影响行数={}", appId, modelId, rows);
    }

    private AppModelAuthEntity getAuthOrThrow(String appId, String modelId) {
        return Optional.ofNullable(authMapper.selectOne(new LambdaQueryWrapper<AppModelAuthEntity>()
                .eq(AppModelAuthEntity::getAppId, appId).eq(AppModelAuthEntity::getModelId, modelId)))
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,
                        "auth: appId=" + appId + " modelId=" + modelId));
    }
}
