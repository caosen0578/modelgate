package com.pab.ficc.ibp.modelgate.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.pab.ficc.ibp.modelgate.server.cache.GatewayCacheManager;
import com.pab.ficc.ibp.modelgate.server.common.BusinessException;
import com.pab.ficc.ibp.modelgate.server.common.ErrorCode;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.pab.ficc.ibp.modelgate.server.crypto.CryptoService;
import com.pab.ficc.ibp.modelgate.server.domain.bo.ModelQueryBO;
import com.pab.ficc.ibp.modelgate.server.domain.entity.AppModelAuthEntity;
import com.pab.ficc.ibp.modelgate.server.domain.entity.ModelCredentialEntity;
import com.pab.ficc.ibp.modelgate.server.domain.entity.ModelInstanceEntity;
import com.pab.ficc.ibp.modelgate.server.mapper.AppModelAuthMapper;
import com.pab.ficc.ibp.modelgate.server.mapper.ModelCredentialMapper;
import com.pab.ficc.ibp.modelgate.server.mapper.ModelMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ModelService {

    private final ModelMapper modelMapper;
    private final ModelCredentialMapper credentialMapper;
    private final AppModelAuthMapper authMapper;
    private final CryptoService cryptoService;
    private final GatewayCacheManager cacheManager;

    public List<ModelInstanceEntity> findAll() {
        return modelMapper.selectList(null);
    }

    /**
     * 分页查模型，按 id 倒序（新建的在前）。
     *
     * <p>分页条件由前端经 {@link ModelQueryBO}（继承行内 {@code BaseBo}）传入，
     * 直接取 {@code pageIndex} / {@code pageSize} 构造分页对象，不额外做收敛。
     *
     * <p><b>必须有稳定排序</b>：MySQL 不保证无 ORDER BY 时的行序，翻页会出现重复行/漏行。
     *
     * <p>keyword 非空时模糊匹配 modelId 或 modelName；provider / enabled 为空则该条件不参与。
     */
    public IPage<ModelInstanceEntity> page(ModelQueryBO query) {
        ModelQueryBO q = query != null ? query : new ModelQueryBO();
        String keyword = q.getKeyword();
        boolean hasKeyword = keyword != null && !keyword.isBlank();
        String provider = q.getProvider();
        Boolean enabled = q.getEnabled();

        return modelMapper.selectPage(new Page<>(q.getPageIndex(), q.getPageSize()),
                new LambdaQueryWrapper<ModelInstanceEntity>()
                        .and(hasKeyword, w -> w.like(ModelInstanceEntity::getModelId, keyword.trim())
                                .or().like(ModelInstanceEntity::getModelName, keyword.trim()))
                        .eq(provider != null && !provider.isBlank(), ModelInstanceEntity::getProvider, provider)
                        .eq(enabled != null, ModelInstanceEntity::getEnabled, enabled)
                        .orderByDesc(ModelInstanceEntity::getId));
    }

    public ModelInstanceEntity findByModelId(String modelId) {
        return Optional.ofNullable(modelMapper.selectOne(
                new LambdaQueryWrapper<ModelInstanceEntity>().eq(ModelInstanceEntity::getModelId, modelId)))
                .orElseThrow(() -> new BusinessException(ErrorCode.MODEL_NOT_FOUND, modelId));
    }

    public Optional<ModelInstanceEntity> getModelCached(String modelId) {
        return cacheManager.getModel(modelId, () -> Optional.ofNullable(
                modelMapper.selectOne(new LambdaQueryWrapper<ModelInstanceEntity>().eq(ModelInstanceEntity::getModelId, modelId))));
    }

    @Transactional(transactionManager = "tdsqlTransactionManager", rollbackFor = Exception.class)
    public ModelInstanceEntity create(ModelInstanceEntity model, ModelCredentialEntity credential) {
        boolean exists = modelMapper.exists(
                new LambdaQueryWrapper<ModelInstanceEntity>().eq(ModelInstanceEntity::getModelId, model.getModelId()));
        if (exists) {
            log.warn("[ModelService][create] modelId 已存在，拒绝创建: modelId={}", model.getModelId());
            throw new BusinessException(ErrorCode.RESOURCE_ALREADY_EXISTS, "modelId: " + model.getModelId());
        }
        model.setCreateTime(LocalDateTime.now());
        model.setUpdateTime(LocalDateTime.now());
        modelMapper.insert(model);
        log.info("[ModelService][create] 模型已入库: modelId={}, id={}, upstreamUrl={}",
                model.getModelId(), model.getId(), model.getUpstreamUrl());

        if (credential != null && credential.getAuthType() != null) {
            credential.setModelId(model.getModelId());
            if (credential.getSecretKey() != null) {
                credential.setSecretKey(cryptoService.encrypt(credential.getSecretKey()));
            }
            credential.setCreateTime(LocalDateTime.now());
            credential.setUpdateTime(LocalDateTime.now());
            credentialMapper.insert(credential);
            log.info("[ModelService][create] 上游凭证已入库: modelId={}, authType={}, secretKey=***{}",
                    model.getModelId(), credential.getAuthType(), tail4(credential.getSecretKey()));
        } else {
            log.info("[ModelService][create] 未附带上游凭证，跳过凭证入库: modelId={}", model.getModelId());
        }
        // 404 的空结果本身也会缓存；提交后主动清理，避免“先调用后创建”的模型继续 404 到 TTL 结束。
        evictModelScopedCachesAfterCommit(Set.of(model.getModelId()), Set.of());
        return model;
    }

    /** 上游鉴权类型：不鉴权 */
    private static final String AUTH_TYPE_NONE = "none";

    /**
     * 更新模型信息及其上游凭证。
     *
     * @param credential 为 null 表示本次不修改凭证；非 null 时按 {@link #applyCredentialUpdate} 落库
     */
    @Transactional(transactionManager = "tdsqlTransactionManager", rollbackFor = Exception.class)
    public ModelInstanceEntity update(String modelId, ModelInstanceEntity updates, ModelCredentialEntity credential) {
        return update(modelId, updates, credential, null);
    }

    /**
     * 更新模型信息及其上游凭证，可选地<b>改掉 model_id 本身</b>。
     *
     * @param newModelId 新的对外模型 ID；为空或与原值相同表示不改名
     *
     * <h3>改名的连带影响（调用方必须知晓）</h3>
     * <ul>
     *   <li><b>旧 ID 会失效</b>：当前实例清缓存后下一次请求即 404；多副本的其他实例按下述
     *       缓存 TTL 收敛。这是改名不可避免的后果，不是缺陷。</li>
     *   <li><b>凭证与授权自动跟随</b>：靠 {@code mg_model_credential} /
     *       {@code mg_app_model_auth} 两个外键的 {@code ON UPDATE CASCADE}
     *       （见 {@code sql/upgrade_20260819_model_id_cascade.sql}）。
     *       <b>没执行那个脚本的话，这里会被外键直接拒绝</b>——本方法会把原始错误往上抛，
     *       并在日志里点明原因，不做静默降级。</li>
     *   <li><b>历史审计与报文保留旧 ID</b>：这两张表没有外键、不参与级联。
     *       这是<b>有意的</b>——那些调用当时确实打的是旧 ID，改掉就是篡改历史。
     *       代价是用量统计会按新旧两个名字裂开，需要人工合并口径。</li>
     *   <li><b>日/月配额从新键重新累计</b>：Redis 配额键包含对外 modelId。这里不在线搬迁计数，
     *       因为改名瞬间仍可能有在途请求和延迟回写的 Token，直接复制/相加会造成漏计或重复计数。
     *       有严格配额要求时应在低流量窗口改名，或新建模型并逐步迁移调用方。</li>
     *   <li><b>多副本不是同时失效</b>：提交后的主动失效只作用于处理本次管理请求的实例；
     *       当前没有跨实例缓存广播，其他实例依靠模型/凭证 30 秒、授权 60 秒 TTL 收敛。</li>
     * </ul>
     *
     * <p><b>多数情况下你不该改名</b>：如果只是想「对外换个名字」，正确做法是新建一条
     * 模型记录、用 {@code upstream_model_id} 指向上游真实 ID，旧记录保持不动——
     * 旧调用方不受影响，历史统计也不裂。改名只适合「当初 ID 就建错了、且还没人用」。
     */
    @Transactional(transactionManager = "tdsqlTransactionManager", rollbackFor = Exception.class)
    public ModelInstanceEntity update(String modelId, ModelInstanceEntity updates,
                                      ModelCredentialEntity credential, String newModelId) {
        findByModelId(modelId);

        boolean rename = newModelId != null && !newModelId.isBlank() && !newModelId.equals(modelId);
        if (rename) {
            // 目标 ID 不能已被占用——uk_model_id 是 (model_id, is_del) 复合唯一键，
            // 直接撞上去只会得到一个晦涩的 DuplicateKeyException，不如先查清楚给明确提示
            if (findByModelIdOrNull(newModelId) != null) {
                throw new BusinessException(ErrorCode.RESOURCE_ALREADY_EXISTS,
                        "目标模型 ID 已存在: " + newModelId);
            }
            updates.setModelId(newModelId);

            // ⚠️ 保住「上游收到的模型身份」不被改名连带改掉。
            //
            // resolveUpstreamModelId() 在 upstream_model_id 为空时回退成 model_id，
            // 也就是说：一个上游 ID 留空的模型，改名前上游收到旧 ID、改名后就变成收到新 ID。
            // 而运维改的本意通常只是**对外那个标签**（如为了绕开平台"模型 ID 重名"限制），
            // 上游根本不认识这个新名字 —— 结果是改完之后该模型的每一个请求都被上游拒绝，
            // 而且没有任何一层会报错提示，只能靠上游返回的 model not found 去猜。
            //
            // 所以：提交值为空时，把**改名前的 ID** 回填进去，让上游收到的东西一个字节都不变。
            // 运维若确实想让上游也换名，显式填新 ID 即可（此处尊重非空的提交值，不覆盖）。
            String preservedUpstreamId = resolveUpstreamIdOnRename(modelId, updates.getUpstreamModelId());
            if (!preservedUpstreamId.equals(updates.getUpstreamModelId())) {
                updates.setUpstreamModelId(preservedUpstreamId);
                log.warn("[ModelService][update] 改名时上游真实模型 ID 为空，已自动回填为改名前的 {}，"
                        + "以保证上游收到的模型身份不变。若确实希望上游也改用新 ID，"
                        + "请在模型配置里把「上游真实模型 ID」显式填成 {}", modelId, newModelId);
            }

            log.warn("[ModelService][update] 模型改名: {} -> {}。"
                            + "凭证与授权将由外键级联跟随；旧 ID 调用方将在缓存收敛后 404；"
                            + "历史审计/报文保留旧 ID，日/月 Redis 配额从新 ID 重新累计；"
                            + "多副本环境最长按授权缓存 TTL 60 秒收敛；"
                            + "上游真实模型 ID={}",
                    modelId, newModelId, preservedUpstreamId);
        } else {
            updates.setModelId(modelId);
        }
        // ON UPDATE CASCADE 会改 mg_app_model_auth.model_id，但应用授权缓存是按 appId 聚合的；
        // 必须在改名前记下受影响 App，否则提交后已经无法再用旧 ID 查到它们。
        Set<String> affectedAppIds = rename ? findAuthorizedAppIds(modelId) : Set.of();
        updates.setUpdateTime(LocalDateTime.now());

        try {
            modelMapper.update(updates,
                    new LambdaQueryWrapper<ModelInstanceEntity>().eq(ModelInstanceEntity::getModelId, modelId));
        } catch (Exception e) {
            if (rename) {
                // 最常见的原因就是外键还是 ON UPDATE NO ACTION（级联脚本没执行）
                log.error("[ModelService][update] 模型改名失败 {} -> {}。"
                                + "若报外键约束错误，说明 sql/upgrade_20260819_model_id_cascade.sql 尚未执行"
                                + "（该脚本把 fk_modelcred_model / fk_auth_model 改为 ON UPDATE CASCADE）: {}",
                        modelId, newModelId, e.getMessage());
            }
            throw e;
        }

        String effectiveId = rename ? newModelId : modelId;

        if (credential != null && credential.getAuthType() != null) {
            applyCredentialUpdate(effectiveId, credential);
        } else {
            log.debug("[ModelService][update] 未携带凭证，跳过凭证更新: modelId={}", effectiveId);
        }

        // 只在事务真正提交后清缓存。若在提交前先清，并发请求可能从尚未提交的数据库里
        // 重新装入旧值，随后事务提交，缓存反而会一直脏到 TTL 结束。
        Set<String> changedModelIds = rename ? Set.of(modelId, effectiveId) : Set.of(modelId);
        evictModelScopedCachesAfterCommit(changedModelIds, affectedAppIds);
        log.info("[ModelService][update] 模型已更新，提交后清缓存: modelId={}{}, upstreamUrl={}",
                effectiveId, rename ? "（由 " + modelId + " 改名而来）" : "", updates.getUpstreamUrl());
        return findByModelId(effectiveId);
    }

    /**
     * 改名时决定 {@code upstream_model_id} 的最终取值。
     *
     * <p>抽成独立静态方法是为了能单独回归——这条规则一旦写反，
     * 后果是"改完名字该模型所有请求被上游拒绝"，且链路上没有任何一层会报错。
     *
     * @param oldModelId          改名<b>前</b>的对外 ID
     * @param submittedUpstreamId 本次提交的上游真实 ID（可能为 null/空）
     * @return 提交值非空则原样返回（尊重运维的显式选择）；为空则返回 {@code oldModelId}，
     *         使上游收到的模型身份在改名前后保持一致
     */
    static String resolveUpstreamIdOnRename(String oldModelId, String submittedUpstreamId) {
        return submittedUpstreamId == null || submittedUpstreamId.isBlank()
                ? oldModelId
                : submittedUpstreamId;
    }

    /** 查不到返回 null 而不是抛异常，供「目标 ID 是否已被占用」这类判断使用 */
    private ModelInstanceEntity findByModelIdOrNull(String modelId) {
        return modelMapper.selectOne(
                new LambdaQueryWrapper<ModelInstanceEntity>().eq(ModelInstanceEntity::getModelId, modelId));
    }

    /**
     * 落库上游凭证。
     *
     * <p>两条必须遵守的语义：
     * <ul>
     *   <li>{@code authType=none}：清空 accessKey / secretKey——"不鉴权"就不该在库里留着密钥，
     *       否则适配器仍可能拿它拼鉴权头。</li>
     *   <li>{@code secretKey} 留空：沿用库中原密钥。编辑页出于安全不回显密钥，用户不重填是常态，
     *       此时**不得**把已存密钥覆盖成空。</li>
     * </ul>
     *
     * <p>用 {@code LambdaUpdateWrapper} 显式 set 而非实体更新：MyBatis-Plus 默认 NOT_NULL 策略会
     * 跳过 null 字段，走实体更新会导致 authType=none 时清不掉密钥列。
     */
    private void applyCredentialUpdate(String modelId, ModelCredentialEntity incoming) {
        ModelCredentialEntity existing = credentialMapper.selectOne(
                new LambdaQueryWrapper<ModelCredentialEntity>().eq(ModelCredentialEntity::getModelId, modelId));

        String authType = incoming.getAuthType();
        String accessKey;
        String secretKey;
        // extraConfig 前端未必回传（如 Azure 的 deployment/version），缺省时保留库中原值，不能覆盖成 null
        String extraConfig = isBlank(incoming.getExtraConfig()) && existing != null
                ? existing.getExtraConfig() : incoming.getExtraConfig();

        if (AUTH_TYPE_NONE.equals(authType)) {
            accessKey = null;
            secretKey = null;
        } else {
            accessKey = isBlank(incoming.getAccessKey()) && existing != null
                    ? existing.getAccessKey() : incoming.getAccessKey();
            if (isBlank(incoming.getSecretKey())) {
                secretKey = existing != null ? existing.getSecretKey() : null;  // 库中已是密文，原样保留
            } else {
                secretKey = cryptoService.encrypt(incoming.getSecretKey());
            }
        }

        if (existing == null) {
            ModelCredentialEntity c = new ModelCredentialEntity();
            c.setModelId(modelId);
            c.setAuthType(authType);
            c.setAccessKey(accessKey);
            c.setSecretKey(secretKey);
            c.setExtraConfig(extraConfig);
            c.setCreateTime(LocalDateTime.now());
            c.setUpdateTime(LocalDateTime.now());
            credentialMapper.insert(c);
            log.info("[ModelService][update] 凭证不存在，已新建: modelId={}, authType={}", modelId, authType);
            return;
        }

        credentialMapper.update(null, new LambdaUpdateWrapper<ModelCredentialEntity>()
                .eq(ModelCredentialEntity::getModelId, modelId)
                .set(ModelCredentialEntity::getAuthType, authType)
                .set(ModelCredentialEntity::getAccessKey, accessKey)
                .set(ModelCredentialEntity::getSecretKey, secretKey)
                .set(ModelCredentialEntity::getExtraConfig, extraConfig)
                .set(ModelCredentialEntity::getUpdateTime, LocalDateTime.now()));

        log.info("[ModelService][update] 凭证已更新: modelId={}, authType={}({}), secretKey={}",
                modelId, existing.getAuthType(), authType,
                AUTH_TYPE_NONE.equals(authType) ? "已清空"
                        : isBlank(incoming.getSecretKey()) ? "沿用原值" : "***" + tail4(secretKey));
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /** 查询全部模型的凭证，按 modelId 归集；用于列表页回显鉴权类型，避免 N+1 查询 */
    public Map<String, ModelCredentialEntity> findAllCredentials() {
        return credentialMapper.selectList(null).stream()
                .collect(Collectors.toMap(ModelCredentialEntity::getModelId, c -> c, (a, b) -> a));
    }

    /**
     * 密钥类字段只保留末 4 位用于日志定位（见 03-编码规范 §7：禁止打印完整密钥）。
     */
    private String tail4(String secret) {
        if (secret == null || secret.length() < 4) {
            return "";
        }
        return secret.substring(secret.length() - 4);
    }

    public void enable(String modelId) {
        findByModelId(modelId);
        modelMapper.update(null, new LambdaUpdateWrapper<ModelInstanceEntity>()
                .set(ModelInstanceEntity::getEnabled, true)
                .set(ModelInstanceEntity::getUpdateTime, LocalDateTime.now())
                .eq(ModelInstanceEntity::getModelId, modelId));
        cacheManager.evictModelCache(modelId);
    }

    public void disable(String modelId) {
        findByModelId(modelId);
        modelMapper.update(null, new LambdaUpdateWrapper<ModelInstanceEntity>()
                .set(ModelInstanceEntity::getEnabled, false)
                .set(ModelInstanceEntity::getUpdateTime, LocalDateTime.now())
                .eq(ModelInstanceEntity::getModelId, modelId));
        cacheManager.evictModelCache(modelId);
    }

    @Transactional(transactionManager = "tdsqlTransactionManager", rollbackFor = Exception.class)
    public void delete(String modelId) {
        findByModelId(modelId);
        boolean hasActiveAuths = authMapper.exists(
                new LambdaQueryWrapper<AppModelAuthEntity>()
                        .eq(AppModelAuthEntity::getModelId, modelId)
                        .eq(AppModelAuthEntity::getEnabled, true));
        if (hasActiveAuths) {
            throw new BusinessException(ErrorCode.ACTIVE_REFS_EXIST, "model has active app authorizations");
        }
        Set<String> affectedAppIds = findAuthorizedAppIds(modelId);
        credentialMapper.delete(new LambdaQueryWrapper<ModelCredentialEntity>().eq(ModelCredentialEntity::getModelId, modelId));
        modelMapper.delete(new LambdaQueryWrapper<ModelInstanceEntity>().eq(ModelInstanceEntity::getModelId, modelId));
        // 模型和解密后的上游凭证必须一起失效；授权缓存也可能仍持有已禁用的历史授权。
        evictModelScopedCachesAfterCommit(Set.of(modelId), affectedAppIds);
    }

    /** 查询某模型当前关联的 App，只取 app_id，供模型改名/删除后的授权缓存失效使用。 */
    private Set<String> findAuthorizedAppIds(String modelId) {
        return authMapper.selectList(new LambdaQueryWrapper<AppModelAuthEntity>()
                        .select(AppModelAuthEntity::getAppId)
                        .eq(AppModelAuthEntity::getModelId, modelId))
                .stream()
                .map(AppModelAuthEntity::getAppId)
                .collect(Collectors.toSet());
    }

    /**
     * 在数据库提交后失效模型相关缓存。
     *
     * <p>改名必须同时清旧/新模型配置、旧/新解密凭证以及所有受影响 App 的授权列表；
     * 任一项遗漏都会在缓存 TTL 内造成旧 ID 仍可路由或新 ID 被误判为未授权。
     * 本方法清的是当前 JVM 的 Caffeine；项目尚未实现跨实例失效广播，其他副本仍按 TTL 收敛。
     */
    private void evictModelScopedCachesAfterCommit(Set<String> modelIds, Set<String> appIds) {
        Runnable eviction = () -> {
            modelIds.forEach(id -> {
                cacheManager.evictModelCache(id);
                cacheManager.evictModelCredentialCache(id);
            });
            appIds.forEach(cacheManager::evictModelAuthCache);
            log.debug("[ModelService] 模型关联缓存已失效: modelIds={}, appIds={}", modelIds, appIds);
        };

        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    eviction.run();
                }
            });
        } else {
            // 防御性兼容非代理调用/无事务测试；生产 Controller 路径会走上面的 afterCommit。
            eviction.run();
        }
    }

    public Optional<ModelCredentialEntity> getCredential(String modelId) {
        return Optional.ofNullable(credentialMapper.selectOne(
                new LambdaQueryWrapper<ModelCredentialEntity>().eq(ModelCredentialEntity::getModelId, modelId)));
    }

    public ModelCredentialEntity getCredentialDecrypted(String modelId) {
        return cacheManager.getModelCredential(modelId, () -> {
            ModelCredentialEntity cred = credentialMapper.selectOne(
                    new LambdaQueryWrapper<ModelCredentialEntity>().eq(ModelCredentialEntity::getModelId, modelId));
            if (cred != null && cred.getSecretKey() != null) {
                cred.setSecretKey(cryptoService.decrypt(cred.getSecretKey()));
            }
            return Optional.ofNullable(cred);
        }).orElse(null);
    }
}
