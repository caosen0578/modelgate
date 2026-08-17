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
import com.pab.ficc.ibp.modelgate.server.domain.entity.ModelCredentialEntity;
import com.pab.ficc.ibp.modelgate.server.domain.entity.ModelInstanceEntity;
import com.pab.ficc.ibp.modelgate.server.mapper.AppModelAuthMapper;
import com.pab.ficc.ibp.modelgate.server.mapper.ModelCredentialMapper;
import com.pab.ficc.ibp.modelgate.server.mapper.ModelMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
        return model;
    }

    /** 上游鉴权类型：不鉴权 */
    private static final String AUTH_TYPE_NONE = "none";

    /**
     * 更新模型信息及其上游凭证。
     *
     * @param credential 为 null 表示本次不修改凭证；非 null 时按 {@link #applyCredentialUpdate} 落库
     */
    public ModelInstanceEntity update(String modelId, ModelInstanceEntity updates, ModelCredentialEntity credential) {
        findByModelId(modelId);
        updates.setModelId(modelId);
        updates.setUpdateTime(LocalDateTime.now());
        modelMapper.update(updates, new LambdaQueryWrapper<ModelInstanceEntity>().eq(ModelInstanceEntity::getModelId, modelId));

        if (credential != null && credential.getAuthType() != null) {
            applyCredentialUpdate(modelId, credential);
        } else {
            log.debug("[ModelService][update] 未携带凭证，跳过凭证更新: modelId={}", modelId);
        }

        cacheManager.evictModelCache(modelId);
        // 凭证缓存也必须清，否则改了鉴权方式后转发仍用旧凭证
        cacheManager.evictModelCredentialCache(modelId);
        log.info("[ModelService][update] 模型已更新并清缓存: modelId={}, upstreamUrl={}", modelId, updates.getUpstreamUrl());
        return findByModelId(modelId);
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

    public void delete(String modelId) {
        findByModelId(modelId);
        boolean hasActiveAuths = authMapper.exists(
                new LambdaQueryWrapper<com.pab.ficc.ibp.modelgate.server.domain.entity.AppModelAuthEntity>()
                        .eq(com.pab.ficc.ibp.modelgate.server.domain.entity.AppModelAuthEntity::getModelId, modelId)
                        .eq(com.pab.ficc.ibp.modelgate.server.domain.entity.AppModelAuthEntity::getEnabled, true));
        if (hasActiveAuths) {
            throw new BusinessException(ErrorCode.ACTIVE_REFS_EXIST, "model has active app authorizations");
        }
        credentialMapper.delete(new LambdaQueryWrapper<ModelCredentialEntity>().eq(ModelCredentialEntity::getModelId, modelId));
        modelMapper.delete(new LambdaQueryWrapper<ModelInstanceEntity>().eq(ModelInstanceEntity::getModelId, modelId));
        cacheManager.evictModelCache(modelId);
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
