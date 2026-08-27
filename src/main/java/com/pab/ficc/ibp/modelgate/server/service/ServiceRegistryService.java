package com.pab.ficc.ibp.modelgate.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.pab.ficc.ibp.modelgate.server.cache.GatewayCacheManager;
import com.pab.ficc.ibp.modelgate.server.common.BusinessException;
import com.pab.ficc.ibp.modelgate.server.common.ErrorCode;
import com.pab.ficc.ibp.modelgate.server.crypto.CryptoService;
import com.pab.ficc.ibp.modelgate.server.domain.bo.CreateServiceRequestBO;
import com.pab.ficc.ibp.modelgate.server.domain.bo.ServiceQueryBO;
import com.pab.ficc.ibp.modelgate.server.domain.bo.UpdateServiceRequestBO;
import com.pab.ficc.ibp.modelgate.server.domain.entity.AppServiceAuthEntity;
import com.pab.ficc.ibp.modelgate.server.domain.entity.ServiceCredentialEntity;
import com.pab.ficc.ibp.modelgate.server.domain.entity.ServiceEntity;
import com.pab.ficc.ibp.modelgate.server.mapper.AppServiceAuthMapper;
import com.pab.ficc.ibp.modelgate.server.mapper.ServiceCredentialMapper;
import com.pab.ficc.ibp.modelgate.server.mapper.ServiceMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 通用服务注册的管理面服务（{@code mg_service} + {@code mg_service_credential}）。
 *
 * <p>与 {@code ModelService} 平级。命名用 {@code ServiceRegistryService} 而不是
 * {@code ServiceService}——后者读起来像重复词，且和 Spring 的 {@code @Service} 注解撞概念。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ServiceRegistryService {

    /**
     * 服务编码格式：小写字母数字连字符，2~32 位，不能以连字符开头。
     *
     * <p><b>为什么必须严格</b>：编码是对外 URL {@code /svc/{编码}/**} 的一个路径段。
     * 放进大写字母会造成"页面上配的和实际能调通的不一致"（HTTP 路径大小写敏感）；
     * 放进斜杠会直接让路由分段错乱，把后面的路径吃掉。
     * 而且编码<b>创建后不可修改</b>，错了只能删了重建，所以必须在入口挡住。
     */
    private static final Pattern SERVICE_ID_PATTERN = Pattern.compile("^[a-z0-9][a-z0-9-]{1,31}$");

    /** 上游鉴权类型：不注入任何凭证 */
    private static final String AUTH_TYPE_NONE = "none";

    private final ServiceMapper serviceMapper;
    private final ServiceCredentialMapper credentialMapper;
    private final AppServiceAuthMapper authMapper;
    private final GatewayCacheManager cacheManager;
    private final CryptoService cryptoService;

    // ===================== 查询 =====================

    public List<ServiceEntity> findAll() {
        return serviceMapper.selectList(
                new LambdaQueryWrapper<ServiceEntity>().orderByDesc(ServiceEntity::getCreateTime));
    }

    public IPage<ServiceEntity> page(ServiceQueryBO query) {
        LambdaQueryWrapper<ServiceEntity> w = new LambdaQueryWrapper<>();
        if (query.getKeyword() != null && !query.getKeyword().isBlank()) {
            String kw = query.getKeyword().trim();
            w.and(q -> q.like(ServiceEntity::getServiceId, kw)
                    .or().like(ServiceEntity::getServiceName, kw));
        }
        if (query.getEnabled() != null) {
            w.eq(ServiceEntity::getEnabled, query.getEnabled());
        }
        if (query.getSse() != null) {
            w.eq(ServiceEntity::getSse, query.getSse());
        }
        w.orderByDesc(ServiceEntity::getCreateTime);
        return serviceMapper.selectPage(new Page<>(query.getPageIndex(), query.getPageSize()), w);
    }

    public ServiceEntity findByServiceId(String serviceId) {
        ServiceEntity e = serviceMapper.selectOne(
                new LambdaQueryWrapper<ServiceEntity>().eq(ServiceEntity::getServiceId, serviceId));
        if (e == null) {
            throw new BusinessException(ErrorCode.SERVICE_NOT_FOUND, "serviceId: " + serviceId);
        }
        return e;
    }

    /** 转发热路径用：走 Caffeine L1（30s），空结果同样缓存。 */
    public Optional<ServiceEntity> getServiceCached(String serviceId) {
        return cacheManager.getService(serviceId, () -> Optional.ofNullable(serviceMapper.selectOne(
                new LambdaQueryWrapper<ServiceEntity>().eq(ServiceEntity::getServiceId, serviceId))));
    }

    public Optional<ServiceCredentialEntity> getCredential(String serviceId) {
        return Optional.ofNullable(credentialMapper.selectOne(
                new LambdaQueryWrapper<ServiceCredentialEntity>()
                        .eq(ServiceCredentialEntity::getServiceId, serviceId)));
    }

    /**
     * 转发热路径用：取凭证并<b>解密</b> SK 与 extraHeaders，走 L1（30s）。
     *
     * <p>缓存的是解密后的对象——解密本身有成本（AES-GCM + Base64），
     * 每请求解一次在高频下不划算；而缓存密文再逐请求解密等于没缓存。
     * 代价是明文在堆里驻留最多 30 秒，与模型侧 {@code getCredentialDecrypted} 的取舍一致。
     */
    public ServiceCredentialEntity getCredentialDecrypted(String serviceId) {
        return cacheManager.getServiceCredential(serviceId, () -> getCredential(serviceId).map(c -> {
            ServiceCredentialEntity copy = new ServiceCredentialEntity();
            copy.setServiceId(c.getServiceId());
            copy.setAuthType(c.getAuthType());
            copy.setAccessKey(c.getAccessKey());
            copy.setSecretKey(decryptQuietly(c.getSecretKey(), serviceId, "secretKey"));
            copy.setExtraHeaders(decryptQuietly(c.getExtraHeaders(), serviceId, "extraHeaders"));
            return copy;
        })).orElse(null);
    }

    /**
     * 解密失败时返回 null 而不是抛异常。
     *
     * <p>密文解不开只有两种成因：换了 {@code modelgate.crypto.key}，或有人直接改库写了明文。
     * 两种都是配置问题，不该让<b>整条转发链路</b>崩掉——返回 null 的效果是"这个头不注入"，
     * 上游会给出 401，比网关抛 500 更容易定位。同时打 WARN 留痕。
     */
    private String decryptQuietly(String cipher, String serviceId, String field) {
        if (cipher == null || cipher.isBlank()) {
            return null;
        }
        try {
            return cryptoService.decrypt(cipher);
        } catch (Exception ex) {
            log.warn("[ServiceRegistry] 凭证解密失败，该字段按未配置处理: serviceId={}, field={}, cause={}",
                    serviceId, field, ex.getMessage());
            return null;
        }
    }

    // ===================== 写入 =====================

    @Transactional(transactionManager = "tdsqlTransactionManager", rollbackFor = Exception.class)
    public ServiceEntity create(CreateServiceRequestBO req) {
        String serviceId = req.getServiceId() == null ? null : req.getServiceId().trim();
        validateServiceId(serviceId);

        // 唯一键含 is_del，所以这里 exists 查的是"未删除的同名服务"；
        // 软删过的同名服务不占用编码，可以重建。
        boolean exists = serviceMapper.exists(
                new LambdaQueryWrapper<ServiceEntity>().eq(ServiceEntity::getServiceId, serviceId));
        if (exists) {
            log.warn("[ServiceRegistry][create] 服务编码已存在，拒绝创建: serviceId={}", serviceId);
            throw new BusinessException(ErrorCode.RESOURCE_ALREADY_EXISTS, "serviceId: " + serviceId);
        }

        ServiceEntity e = new ServiceEntity();
        e.setServiceId(serviceId);
        e.setServiceName(req.getServiceName());
        e.setOwnerSystem(req.getOwnerSystem());
        e.setUpstreamUrl(normalizeUpstreamUrl(req.getUpstreamUrl()));
        e.setStripPrefix(req.getStripPrefix() == null ? Boolean.TRUE : req.getStripPrefix());
        e.setTimeoutMs(req.getTimeoutMs() == null ? 0 : req.getTimeoutMs());
        e.setSse(Boolean.TRUE.equals(req.getSse()));
        e.setPayloadLogEnabled(Boolean.TRUE.equals(req.getPayloadLogEnabled()));
        e.setDescription(req.getDescription());
        e.setEnabled(req.getEnabled() == null ? Boolean.TRUE : req.getEnabled());
        e.setCreateTime(LocalDateTime.now());
        e.setUpdateTime(LocalDateTime.now());
        serviceMapper.insert(e);
        log.info("[ServiceRegistry][create] 服务已入库: serviceId={}, id={}, upstreamUrl={}, stripPrefix={}",
                serviceId, e.getId(), e.getUpstreamUrl(), e.shouldStripPrefix());

        ServiceCredentialEntity cred = new ServiceCredentialEntity();
        cred.setServiceId(serviceId);
        cred.setAuthType(req.getAuthType() == null || req.getAuthType().isBlank()
                ? AUTH_TYPE_NONE : req.getAuthType());
        cred.setAccessKey(req.getAccessKey());
        cred.setSecretKey(encryptIfPresent(req.getSecretKey()));
        cred.setExtraHeaders(encryptIfPresent(validateExtraHeaders(req.getExtraHeaders())));
        cred.setCreateTime(LocalDateTime.now());
        cred.setUpdateTime(LocalDateTime.now());
        credentialMapper.insert(cred);
        log.info("[ServiceRegistry][create] 上游凭证已入库: serviceId={}, authType={}", serviceId, cred.getAuthType());

        // 空结果也会被缓存；提交后主动清理，避免"先调用后创建"的服务继续 404 到 TTL 结束
        evictAfterCommit(serviceId);
        return e;
    }

    @Transactional(transactionManager = "tdsqlTransactionManager", rollbackFor = Exception.class)
    public ServiceEntity update(UpdateServiceRequestBO req) {
        ServiceEntity existing = findByServiceId(req.getServiceId());

        LambdaUpdateWrapper<ServiceEntity> w = new LambdaUpdateWrapper<ServiceEntity>()
                .eq(ServiceEntity::getServiceId, req.getServiceId());
        if (req.getServiceName() != null)  w.set(ServiceEntity::getServiceName, req.getServiceName());
        if (req.getOwnerSystem() != null)  w.set(ServiceEntity::getOwnerSystem, req.getOwnerSystem());
        if (req.getUpstreamUrl() != null)  w.set(ServiceEntity::getUpstreamUrl, normalizeUpstreamUrl(req.getUpstreamUrl()));
        if (req.getStripPrefix() != null)  w.set(ServiceEntity::getStripPrefix, req.getStripPrefix());
        if (req.getTimeoutMs() != null)    w.set(ServiceEntity::getTimeoutMs, req.getTimeoutMs());
        if (req.getSse() != null)          w.set(ServiceEntity::getSse, req.getSse());
        if (req.getPayloadLogEnabled() != null) w.set(ServiceEntity::getPayloadLogEnabled, req.getPayloadLogEnabled());
        if (req.getDescription() != null)  w.set(ServiceEntity::getDescription, req.getDescription());
        if (req.getEnabled() != null)      w.set(ServiceEntity::getEnabled, req.getEnabled());
        w.set(ServiceEntity::getUpdateTime, LocalDateTime.now());
        serviceMapper.update(null, w);

        applyCredentialUpdate(req);

        log.info("[ServiceRegistry][update] 服务已更新: serviceId={}", req.getServiceId());
        evictAfterCommit(req.getServiceId());
        return findByServiceId(req.getServiceId());
    }

    /**
     * 凭证更新：<b>字段留空 = 保持原值</b>，不是清空。
     *
     * <p>列表与详情都不回显 SK 明文，所以页面提交上来的 secretKey 多数时候本来就是空的。
     * 若把空当清空，运营改一次描述就会把上游凭证抹掉、且当场看不出来
     * （页面仍显示"已配置"，直到下一次请求被上游 401）。要清空请显式把 authType 改成 none。
     */
    private void applyCredentialUpdate(UpdateServiceRequestBO req) {
        boolean touched = req.getAuthType() != null || req.getAccessKey() != null
                || req.getSecretKey() != null || req.getExtraHeaders() != null;
        if (!touched) {
            return;
        }
        ServiceCredentialEntity current = getCredential(req.getServiceId()).orElse(null);
        if (current == null) {
            ServiceCredentialEntity cred = new ServiceCredentialEntity();
            cred.setServiceId(req.getServiceId());
            cred.setAuthType(req.getAuthType() == null ? AUTH_TYPE_NONE : req.getAuthType());
            cred.setAccessKey(req.getAccessKey());
            cred.setSecretKey(encryptIfPresent(req.getSecretKey()));
            cred.setExtraHeaders(encryptIfPresent(validateExtraHeaders(req.getExtraHeaders())));
            cred.setCreateTime(LocalDateTime.now());
            cred.setUpdateTime(LocalDateTime.now());
            credentialMapper.insert(cred);
            return;
        }

        LambdaUpdateWrapper<ServiceCredentialEntity> cw = new LambdaUpdateWrapper<ServiceCredentialEntity>()
                .eq(ServiceCredentialEntity::getServiceId, req.getServiceId());
        if (req.getAuthType() != null) {
            cw.set(ServiceCredentialEntity::getAuthType, req.getAuthType());
            // 显式切到 none 就是"不再向上游注入凭证"，此时把密钥一并清掉，
            // 避免库里留着一份没人用的密文（换 key 后还会变成解不开的垃圾数据）
            if (AUTH_TYPE_NONE.equals(req.getAuthType())) {
                cw.set(ServiceCredentialEntity::getAccessKey, null);
                cw.set(ServiceCredentialEntity::getSecretKey, null);
            }
        }
        if (req.getAccessKey() != null) {
            cw.set(ServiceCredentialEntity::getAccessKey, req.getAccessKey());
        }
        if (req.getSecretKey() != null && !req.getSecretKey().isBlank()) {
            cw.set(ServiceCredentialEntity::getSecretKey, cryptoService.encrypt(req.getSecretKey()));
        }
        if (req.getExtraHeaders() != null) {
            String validated = validateExtraHeaders(req.getExtraHeaders());
            // 显式传空串 = 清空附加头（这个语义与 SK 不同：附加头在页面上是可见的文本域，
            // 运营清空它就是要删掉，不存在"看不见所以传空"的问题）
            cw.set(ServiceCredentialEntity::getExtraHeaders,
                    validated == null || validated.isBlank() ? null : cryptoService.encrypt(validated));
        }
        cw.set(ServiceCredentialEntity::getUpdateTime, LocalDateTime.now());
        credentialMapper.update(null, cw);
    }

    public void enable(String serviceId) {
        setEnabled(serviceId, true);
    }

    public void disable(String serviceId) {
        setEnabled(serviceId, false);
    }

    private void setEnabled(String serviceId, boolean enabled) {
        findByServiceId(serviceId);
        serviceMapper.update(null, new LambdaUpdateWrapper<ServiceEntity>()
                .eq(ServiceEntity::getServiceId, serviceId)
                .set(ServiceEntity::getEnabled, enabled)
                .set(ServiceEntity::getUpdateTime, LocalDateTime.now()));
        log.info("[ServiceRegistry] 服务{}: serviceId={}", enabled ? "已启用" : "已禁用", serviceId);
        evictAfterCommit(serviceId);
    }

    /**
     * 逻辑删除服务，并级联软删其凭证与全部授权行。
     *
     * <p>级联而非拒绝删除：服务被删后那些授权行已经没有意义，留着会让"接入系统 → 服务授权"
     * 页面显示一堆指向不存在服务的条目。与模型侧的删除语义保持一致。
     */
    @Transactional(transactionManager = "tdsqlTransactionManager", rollbackFor = Exception.class)
    public void delete(String serviceId) {
        findByServiceId(serviceId);

        int authRows = authMapper.delete(new LambdaQueryWrapper<AppServiceAuthEntity>()
                .eq(AppServiceAuthEntity::getServiceId, serviceId));
        credentialMapper.delete(new LambdaQueryWrapper<ServiceCredentialEntity>()
                .eq(ServiceCredentialEntity::getServiceId, serviceId));
        serviceMapper.delete(new LambdaQueryWrapper<ServiceEntity>()
                .eq(ServiceEntity::getServiceId, serviceId));

        log.info("[ServiceRegistry] 服务已删除（逻辑）: serviceId={}, 级联软删授权行={}", serviceId, authRows);
        evictAfterCommit(serviceId);
        // 授权缓存是按 appId 组织的，删服务会影响到所有授权过它的 app，逐个失效代价高，
        // 直接全清——删服务是低频管理操作，全清的代价是各 app 下次请求多查一次 DB。
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                cacheManager.evictAllServiceAuthCache();
            }
        });
    }

    // ===================== 工具 =====================

    private void validateServiceId(String serviceId) {
        if (serviceId == null || !SERVICE_ID_PATTERN.matcher(serviceId).matches()) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER,
                    "服务编码只能是小写字母、数字、连字符，2~32 位且不能以连字符开头，当前值: " + serviceId);
        }
    }

    /**
     * 上游地址规范化：去掉末尾斜杠。
     *
     * <p>转发时是 {@code upstreamUrl + 剩余路径}，剩余路径必定以 {@code /} 开头，
     * 上游地址再带一个尾斜杠就会拼出 {@code //}。有的上游把 {@code //} 当成不同的路由、直接 404，
     * 排查时极难想到是配置里多打了一个斜杠。
     */
    private String normalizeUpstreamUrl(String url) {
        if (url == null) {
            return null;
        }
        String trimmed = url.trim().replaceAll("/+$", "");
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER,
                    "上游地址必须以 http:// 或 https:// 开头，当前值: " + url);
        }
        return trimmed;
    }

    /**
     * 校验附加请求头是 JSON 对象且值都是字符串。
     *
     * <p>在入口挡住而不是转发时才发现：这段内容加密后落库，转发时解密再解析，
     * 那时格式错误只能体现为"该服务所有请求都少了几个头"，而页面上看起来配置得好好的。
     */
    private String validateExtraHeaders(String json) {
        if (json == null || json.isBlank()) {
            return json;
        }
        String s = json.trim();
        if (!s.startsWith("{") || !s.endsWith("}")) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "附加请求头必须是 JSON 对象，如 {\"X-Tenant\":\"ficc\"}");
        }
        return s;
    }

    private String encryptIfPresent(String plaintext) {
        return plaintext == null || plaintext.isBlank() ? null : cryptoService.encrypt(plaintext);
    }

    /**
     * 事务提交后再清缓存。
     *
     * <p>在事务内清会有个窗口：清完但还没提交时，若有并发请求回源，读到的是<b>旧数据</b>
     * 并把它重新写进缓存，于是缓存里躺着旧值直到 TTL 到期——改了配置却"不生效"。
     */
    private void evictAfterCommit(String serviceId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            cacheManager.evictServiceCache(serviceId);
            cacheManager.evictServiceCredentialCache(serviceId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                cacheManager.evictServiceCache(serviceId);
                cacheManager.evictServiceCredentialCache(serviceId);
            }
        });
    }
}
