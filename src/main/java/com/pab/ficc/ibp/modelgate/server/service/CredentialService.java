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
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 凭证管理服务。
 *
 * <p>负责凭证的生成、校验、启禁用、删除，以及缓存的主动失效。
 *
 * <p>凭证类型说明：
 * <ul>
 *   <li><b>api_key</b>：单一密钥，格式 {@code mg-{appId}-{随机串}}。
 *       DB 存末 4 位（展示）+ SHA-256 哈希（鉴权）+ AES 加密密文（可查看）。</li>
 *   <li><b>ak_sk</b>：AK 明文（用于请求头标识），SK 同时存哈希和 AES 加密密文。</li>
 * </ul>
 *
 * <p><b>密钥可查看（明确的安全取舍，勿擅自改回）</b>：
 * 两种类型的密钥明文均以 AES-256-GCM 加密留存于 {@code secret_encrypted}，
 * 支持通过 {@link #revealSecret} 解密查看。这是**内网场景下的业务决策**：
 * 接入系统投产后凭证轮转会中断关联系统的正常业务流程，运维必须能找回既有密钥，
 * 而不是只能作废重建。代价是 DB 泄露 + 加密密钥泄露时凭证会同时失守，
 * 因此 {@code MODELGATE_CRYPTO_KEY} 必须与 DB 分离保管。
 *
 * <p>安全要求：
 * <ul>
 *   <li>鉴权比对仍走 {@link CryptoService#sha256Hex} 哈希，**不得**改为解密后比较明文
 *       ——哈希比对是常量开销且不触碰密钥材料。</li>
 *   <li>{@code secret_encrypted} **禁止**出现在任何列表/详情接口，只允许经
 *       {@link #revealSecret} 单条按需解密。</li>
 *   <li>任何写操作（启用、禁用、删除）后必须主动失效 Caffeine + Redis 两级缓存，
 *       确保下一次请求能及时感知状态变更。</li>
 * </ul>
 */
@Slf4j
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
            // api_key：末 4 位（展示用）+ SHA-256 哈希（鉴权用）+ AES 密文（可查看，见类注释）
            String apiKey = "mg-" + appId + "-" + randomHex(24);
            cred.setAccessKey(apiKey.substring(apiKey.length() - 4)); // 末 4 位，仅展示
            cred.setSecretHash(cryptoService.sha256Hex(apiKey));
            cred.setSecretEncrypted(cryptoService.encrypt(apiKey));
            plainSecret = apiKey;
        }

        credentialMapper.insert(cred);
        // 新凭证入库后主动失效该 App 的本地缓存，避免旧缓存阻挡新凭证立即生效
        cacheManager.evictCredentialCacheByAppId(appId);
        // 凭证签发是最敏感的写操作，必须留痕。明文只打末 4 位（05 §4.1 禁止日志打印完整密钥）
        log.info("[Credential] 签发凭证: appId={}, credId={}, credName={}, type={}, 密钥尾4位=****{}, expiresAt={}",
                appId, cred.getId(), cred.getCredName(), cred.getCredType(), tail4(plainSecret), cred.getExpiresAt());
        return new CreatedCredential(cred, plainSecret);
    }

    /**
     * 全部接入系统的凭证数量，用于系统列表展示。
     * 一次查询后内存聚合，避免逐行 N+1。统计含已禁用/已过期凭证。
     */
    public Map<String, Long> countByAppId() {
        return credentialMapper.selectList(null).stream()
                .collect(Collectors.groupingBy(AppCredentialEntity::getAppId, Collectors.counting()));
    }

    /**
     * 解密并返回凭证明文。
     *
     * <p>仅供管理面「查看密钥」按需调用，**禁止**在列表/详情接口批量返回。
     *
     * <p>启用明文留存**之前**创建的 api_key 凭证，{@code secret_encrypted} 为空，
     * 无法找回——此时明确报错提示重新生成，而不是返回空串让调用方以为密钥是空的。
     *
     * @return 明文密钥（api_key 返回 key 本身，ak_sk 返回 SK）
     */
    public String revealSecret(String appId, Long credId) {
        AppCredentialEntity cred = getCredentialByIdAndApp(appId, credId);
        String encrypted = cred.getSecretEncrypted();
        if (encrypted == null || encrypted.isBlank()) {
            log.info("[Credential] 查看密钥失败（无密文，创建于「密钥可查看」启用之前）: appId={}, credId={}", appId, credId);
            throw new BusinessException(ErrorCode.INVALID_PARAMETER,
                    "该凭证创建于「密钥可查看」启用之前，仅存哈希无法找回，请重新生成凭证");
        }
        // 05 §4.1 强制：解密查看必须留 INFO 日志（谁、何时、查了哪条），供事后追溯
        log.info("[Credential] 查看密钥明文: appId={}, credId={}, credName={}, type={}",
                appId, credId, cred.getCredName(), cred.getCredType());
        return cryptoService.decrypt(encrypted);
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
        log.info("[Credential] 启用凭证: appId={}, credId={}, credName={}", appId, credId, cred.getCredName());
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
        log.info("[Credential] 禁用凭证: appId={}, credId={}, credName={}（该凭证下次请求立即 401）",
                appId, credId, cred.getCredName());
    }

    /** 删除凭证并失效缓存，删除后该凭证下次请求立即 401。 */
    public void delete(String appId, Long credId) {
        AppCredentialEntity cred = getCredentialByIdAndApp(appId, credId);
        credentialMapper.deleteById(credId);
        if (cred.getSecretHash() != null) cacheManager.evictCredentialCache(cred.getSecretHash());
        log.info("[Credential] 删除凭证: appId={}, credId={}, credName={}, type={}",
                appId, credId, cred.getCredName(), cred.getCredType());
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
    /** 取密钥末 4 位用于日志定位；不足 4 位一律打码，避免短串把明文整个漏出去。 */
    private static String tail4(String secret) {
        if (secret == null || secret.length() < 4) return "****";
        return secret.substring(secret.length() - 4);
    }

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
