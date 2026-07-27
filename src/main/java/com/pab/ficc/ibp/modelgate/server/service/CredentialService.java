package com.pab.ficc.ibp.modelgate.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.pab.ficc.ibp.modelgate.server.cache.GatewayCacheManager;
import com.pab.ficc.ibp.modelgate.server.common.BusinessException;
import com.pab.ficc.ibp.modelgate.server.common.ErrorCode;
import com.pab.ficc.ibp.modelgate.server.crypto.CryptoService;
import com.pab.ficc.ibp.modelgate.server.domain.entity.AppCredentialEntity;
import com.pab.ficc.ibp.modelgate.server.mapper.AppCredentialMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

/**
 * 凭证管理服务。
 *
 * <p>负责凭证的生成、校验、启禁用、删除，以及缓存的主动失效。
 *
 * <p>凭证类型说明：
 * <ul>
 *   <li><b>api_key</b>：单一密钥，格式 {@code mg-{appId}-{随机串}}。
 *       DB 中存 SHA-256 哈希，明文仅在创建响应中出现一次，之后不可查。</li>
 *   <li><b>ak_sk</b>：AK 明文（用于请求头标识），SK 同时存哈希和 AES 加密密文。
 *       哈希用于鉴权比对，加密密文保留以备内部查看（需要解密密钥）。</li>
 * </ul>
 *
 * <p>安全要求：
 * <ul>
 *   <li>密钥比对必须使用 {@link CryptoService#sha256Hex} + 哈希比对，不能比较明文。</li>
 *   <li>任何写操作（启用、禁用、删除）后必须主动失效 Caffeine + Redis 两级缓存，
 *       确保下一次请求能及时感知状态变更。</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class CredentialService {

    /** 使用 SecureRandom 生成密钥材料，比 Random 具备密码学安全性 */
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final AppCredentialMapper credentialMapper;
    private final CryptoService cryptoService;
    private final GatewayCacheManager cacheManager;

    /** 查询指定 App 下的所有凭证，按创建时间倒序（不含密钥明文）。 */
    public List<AppCredentialEntity> listByAppId(String appId) {
        return credentialMapper.selectList(
                new LambdaQueryWrapper<AppCredentialEntity>().eq(AppCredentialEntity::getAppId, appId)
                        .orderByDesc(AppCredentialEntity::getCreateTime));
    }

    /**
     * 生成并持久化一条新凭证。
     *
     * <p>返回 {@link CreatedCredential}，其中 {@code plainSecret} 是明文密钥，
     * 调用方必须在当次响应中返回给用户，之后系统内部不再持有明文。
     *
     * @param appId   关联的接入系统 ID
     * @param request 凭证配置（credType、expiresAt、remark 等）
     * @return 已保存的凭证实体 + 一次性明文密钥
     */
    public CreatedCredential create(String appId, AppCredentialEntity request) {
        AppCredentialEntity cred = new AppCredentialEntity();
        cred.setAppId(appId);
        cred.setCredName(request.getCredName());
        cred.setCredType(request.getCredType());
        cred.setExpiresAt(request.getExpiresAt());
        cred.setRemark(request.getRemark());
        cred.setEnabled(true);
        cred.setCreateTime(LocalDateTime.now());
        cred.setUpdateTime(LocalDateTime.now());

        String plainSecret;
        if ("ak_sk".equals(request.getCredType())) {
            // AK：明文，格式可读，作为请求标识；SK：DB 存哈希 + AES 加密密文，明文只返回一次
            String ak = "AK_" + appId.replace("-", "_") + "_" + randomHex(8);
            String sk = "SK_" + randomHex(32);
            cred.setAccessKey(ak);
            cred.setSecretHash(cryptoService.sha256Hex(sk));        // 用于鉴权比对
            cred.setSecretEncrypted(cryptoService.encrypt(sk));     // AES-256-GCM 密文，保留备查
            plainSecret = sk;
        } else {
            // api_key：DB 只存末 4 位（展示用）和 SHA-256 哈希（鉴权用）
            String apiKey = "mg-" + appId + "-" + randomHex(24);
            cred.setAccessKey(apiKey.substring(apiKey.length() - 4)); // 末 4 位，仅展示
            cred.setSecretHash(cryptoService.sha256Hex(apiKey));
            plainSecret = apiKey;
        }

        credentialMapper.insert(cred);
        // 新凭证入库后主动失效该 App 的本地缓存，避免旧缓存阻挡新凭证立即生效
        cacheManager.evictCredentialCacheByAppId(appId);
        return new CreatedCredential(cred, plainSecret);
    }

    /**
     * 根据 API Key 的 SHA-256 哈希查询凭证。
     *
     * <p>缓存层级：Caffeine L1（60s TTL）→ Redis L2 → DB。
     * 高频调用路径（每次 Bearer 请求都走这里），L1 命中率通常 > 95%。
     *
     * @param hash SHA-256(api_key) 十六进制字符串
     */
    public Optional<AppCredentialEntity> findBySecretHash(String hash) {
        return cacheManager.getCredential(hash, () -> Optional.ofNullable(
                credentialMapper.selectOne(new LambdaQueryWrapper<AppCredentialEntity>()
                        .eq(AppCredentialEntity::getSecretHash, hash)
                        .eq(AppCredentialEntity::getEnabled, true))));
    }

    /**
     * 根据 AK 查询凭证（AK/SK 鉴权路径）。
     *
     * <p>AK 不走缓存：AK/SK 请求量相对较少，且 AK 查询后还需再做签名验证，
     * 缓存收益不显著；直接查 DB 保证数据新鲜度。
     */
    public Optional<AppCredentialEntity> findByAccessKey(String accessKey) {
        return Optional.ofNullable(credentialMapper.selectOne(
                new LambdaQueryWrapper<AppCredentialEntity>()
                        .eq(AppCredentialEntity::getAccessKey, accessKey)
                        .eq(AppCredentialEntity::getEnabled, true)));
    }

    /**
     * 启用凭证并立即失效该凭证的 Caffeine + Redis 缓存，
     * 确保下一次请求不会命中旧的"已禁用"缓存条目。
     */
    public void enable(String appId, Long credId) {
        AppCredentialEntity cred = getCredentialByIdAndApp(appId, credId);
        credentialMapper.update(null, new LambdaUpdateWrapper<AppCredentialEntity>()
                .set(AppCredentialEntity::getEnabled, true)
                .set(AppCredentialEntity::getUpdateTime, LocalDateTime.now())
                .eq(AppCredentialEntity::getId, credId));
        if (cred.getSecretHash() != null) cacheManager.evictCredentialCache(cred.getSecretHash());
    }

    /**
     * 禁用凭证并立即失效缓存，禁用效果秒级生效（不需等 Caffeine TTL 自然过期）。
     */
    public void disable(String appId, Long credId) {
        AppCredentialEntity cred = getCredentialByIdAndApp(appId, credId);
        credentialMapper.update(null, new LambdaUpdateWrapper<AppCredentialEntity>()
                .set(AppCredentialEntity::getEnabled, false)
                .set(AppCredentialEntity::getUpdateTime, LocalDateTime.now())
                .eq(AppCredentialEntity::getId, credId));
        if (cred.getSecretHash() != null) cacheManager.evictCredentialCache(cred.getSecretHash());
    }

    /** 删除凭证并失效缓存，删除后该凭证下次请求立即 401。 */
    public void delete(String appId, Long credId) {
        AppCredentialEntity cred = getCredentialByIdAndApp(appId, credId);
        credentialMapper.deleteById(credId);
        if (cred.getSecretHash() != null) cacheManager.evictCredentialCache(cred.getSecretHash());
    }

    /**
     * 校验凭证归属，防止跨 App 操作（如用 appA 的 token 删 appB 的凭证）。
     */
    private AppCredentialEntity getCredentialByIdAndApp(String appId, Long credId) {
        AppCredentialEntity cred = credentialMapper.selectById(credId);
        if (cred == null || !cred.getAppId().equals(appId)) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "credentialId: " + credId);
        }
        return cred;
    }

    /**
     * 生成密码学安全的随机字节并编码为 URL-safe Base64（无填充）。
     *
     * @param bytes 随机字节数，生成的字符串长度约为 bytes × 4/3
     */
    private String randomHex(int bytes) {
        byte[] buf = new byte[bytes];
        SECURE_RANDOM.nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }

    /**
     * 凭证创建结果：已持久化的凭证实体 + 一次性明文密钥。
     * plainSecret 在 Controller 层响应给前端后即可丢弃，不应在服务内部存储。
     */
    public record CreatedCredential(AppCredentialEntity credential, String plainSecret) {}
}
