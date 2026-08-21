package com.pab.ficc.ibp.modelgate.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.pab.ficc.ibp.modelgate.server.common.BusinessException;
import com.pab.ficc.ibp.modelgate.server.common.ErrorCode;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.pab.ficc.ibp.modelgate.server.domain.bo.AppQueryBO;
import com.pab.ficc.ibp.modelgate.server.domain.entity.AccessRuleEntity;
import com.pab.ficc.ibp.modelgate.server.domain.entity.AppCredentialEntity;
import com.pab.ficc.ibp.modelgate.server.domain.entity.AppInfoEntity;
import com.pab.ficc.ibp.modelgate.server.domain.entity.AppModelAuthEntity;
import com.pab.ficc.ibp.modelgate.server.mapper.AccessRuleMapper;
import com.pab.ficc.ibp.modelgate.server.mapper.AppCredentialMapper;
import com.pab.ficc.ibp.modelgate.server.mapper.AppMapper;
import com.pab.ficc.ibp.modelgate.server.mapper.AppModelAuthMapper;
import com.pab.ficc.ibp.modelgate.server.cache.GatewayCacheManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class AppService {

    private final AppMapper appMapper;
    private final AppCredentialMapper credentialMapper;
    private final AppModelAuthMapper authMapper;
    private final AccessRuleMapper accessRuleMapper;
    private final GatewayCacheManager cacheManager;

    public List<AppInfoEntity> findAll() {
        return appMapper.selectList(null);
    }

    /**
     * 分页查接入系统，按 id 倒序（新登记的在前）。
     *
     * <p>分页条件由前端经 {@link AppQueryBO}（继承行内 {@code BaseBo}）传入。
     *
     * <p><b>必须有稳定排序</b>：无 ORDER BY 时 MySQL 不保证行序，翻页会重复/漏行。
     *
     * <p>keyword 非空时模糊匹配 appId / systemName / appName / sysCode；enabled 为 null 则不过滤。
     */
    public IPage<AppInfoEntity> page(AppQueryBO query) {
        AppQueryBO q = query != null ? query : new AppQueryBO();
        String keyword = q.getKeyword();
        boolean hasKeyword = keyword != null && !keyword.isBlank();
        Boolean enabled = q.getEnabled();

        return appMapper.selectPage(new Page<>(q.getPageIndex(), q.getPageSize()),
                new LambdaQueryWrapper<AppInfoEntity>()
                        .and(hasKeyword, w -> w.like(AppInfoEntity::getAppId, keyword.trim())
                                .or().like(AppInfoEntity::getSystemName, keyword.trim())
                                .or().like(AppInfoEntity::getAppName, keyword.trim())
                                .or().like(AppInfoEntity::getSysCode, keyword.trim()))
                        .eq(enabled != null, AppInfoEntity::getEnabled, enabled)
                        .orderByDesc(AppInfoEntity::getId));
    }

    public AppInfoEntity findByAppId(String appId) {
        return Optional.ofNullable(appMapper.selectOne(
                new LambdaQueryWrapper<AppInfoEntity>().eq(AppInfoEntity::getAppId, appId)))
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "appId: " + appId));
    }

    /**
     * {@link #findByAppId} 的缓存版本，供 {@code CredentialAuthFilter} 等请求热路径调用。
     *
     * <p><b>2026-08-18 压测优化新增</b>：此前 {@code CredentialAuthFilter} 每个已鉴权请求都调用
     * 不走缓存的 {@link #findByAppId}，压测证实是并发升高时 MySQL 连接池被打满的主因之一。
     * 缓存增删改禁用四处均已同步 evict（见 {@link #evictAppScopedCache}），满足 05 §8「App 禁用
     * 必须立即失效」的要求；相比此前"完全不缓存"，少了一层独立兜底，是本轮明确接受的取舍
     * （见 GatewayCacheManager.appInfoCache 字段注释）。
     */
    public AppInfoEntity findByAppIdCached(String appId) {
        return cacheManager.getAppInfo(appId, () -> findByAppId(appId));
    }

    public AppInfoEntity create(AppInfoEntity app) {
        boolean exists = appMapper.exists(new LambdaQueryWrapper<AppInfoEntity>().eq(AppInfoEntity::getAppId, app.getAppId()));
        if (exists) {
            throw new BusinessException(ErrorCode.RESOURCE_ALREADY_EXISTS, "appId: " + app.getAppId());
        }
        // create_time / update_time / is_del / creater / updater 由 AuditFieldMetaObjectHandler 自动填充，
        // 此处不得手动 set（05 §10）——strictInsertFill 仅在字段为 null 时填充，手动 set 会架空自动填充
        app.setEnabled(true);
        appMapper.insert(app);
        log.info("[App] 新增接入系统: appId={}, systemName={}, appName={}, sysCode={}, owner={}",
                app.getAppId(), app.getSystemName(), app.getAppName(), app.getSysCode(), app.getOwner());
        return app;
    }

    public AppInfoEntity update(String appId, AppInfoEntity updates) {
        findByAppId(appId);
        // update_time / updater 由 AuditFieldMetaObjectHandler 自动填充（05 §10）
        appMapper.update(updates, new LambdaQueryWrapper<AppInfoEntity>().eq(AppInfoEntity::getAppId, appId));
        // 系统可能在本次更新里被停用，凭证准入状态随之改变，必须失效相关缓存
        evictAppScopedCache(appId);
        log.info("[App] 更新接入系统: appId={}, systemName={}, appName={}, owner={}, enabled={}",
                appId, updates.getSystemName(), updates.getAppName(), updates.getOwner(), updates.getEnabled());
        return findByAppId(appId);
    }

    public void enable(String appId) {
        findByAppId(appId);
        appMapper.update(null, new LambdaUpdateWrapper<AppInfoEntity>()
                .set(AppInfoEntity::getEnabled, true)
                .set(AppInfoEntity::getUpdateTime, LocalDateTime.now())
                .eq(AppInfoEntity::getAppId, appId));
        evictAppScopedCache(appId);
        log.info("[App] 启用接入系统: appId={}", appId);
    }

    public void disable(String appId) {
        findByAppId(appId);
        appMapper.update(null, new LambdaUpdateWrapper<AppInfoEntity>()
                .set(AppInfoEntity::getEnabled, false)
                .set(AppInfoEntity::getUpdateTime, LocalDateTime.now())
                .eq(AppInfoEntity::getAppId, appId));
        // 08 §业务规则：App 禁用必须立即失效其凭证，不得等缓存自然过期
        evictAppScopedCache(appId);
        log.info("[App] 禁用接入系统: appId={}（其全部凭证立即失效）", appId);
    }

    public void delete(String appId) {
        findByAppId(appId);
        int rules = accessRuleMapper.delete(new LambdaQueryWrapper<AccessRuleEntity>().eq(AccessRuleEntity::getAppId, appId));
        int auths = authMapper.delete(new LambdaQueryWrapper<AppModelAuthEntity>().eq(AppModelAuthEntity::getAppId, appId));
        int creds = credentialMapper.delete(new LambdaQueryWrapper<AppCredentialEntity>().eq(AppCredentialEntity::getAppId, appId));
        appMapper.delete(new LambdaQueryWrapper<AppInfoEntity>().eq(AppInfoEntity::getAppId, appId));
        evictAppScopedCache(appId);
        // 级联删除的三类子数据条数要打出来：删除是逻辑删除，出问题时这是唯一的影响面记录
        log.info("[App] 删除接入系统: appId={}, 级联删除 访问规则={} 条, 模型授权={} 条, 凭证={} 条",
                appId, rules, auths, creds);
    }

    /**
     * 失效该 App 相关的全部网关缓存。
     *
     * <p>本类此前**一处缓存失效都没有**，而 delete() 会级联删除访问规则/授权/凭证、
     * disable() 会改变准入状态——违反 05 §7「写操作后必须主动失效缓存」。
     * 后果：删除后 60s（L1 TTL）内重建同名 appId，新系统会继承旧系统的授权与来源白名单。
     *
     * <p>注：CredentialAuthFilter 里的准入检查现在走 {@link #findByAppIdCached}（2026-08-18 起，
     * 此前是不走缓存的 {@link #findByAppId}），因此本方法必须把 {@code appInfoCache} 一起
     * evict——漏掉的话会出现"删完/禁用后还能在缓存 TTL 内继续调用"的鉴权绕过，
     * 不再像此前那样有独立于缓存之外的实时兜底。
     */
    private void evictAppScopedCache(String appId) {
        cacheManager.evictCredentialCacheByAppId(appId);
        cacheManager.evictModelAuthCache(appId);
        cacheManager.evictAccessRuleCache(appId);
        cacheManager.evictAppInfoCache(appId);
    }
}
