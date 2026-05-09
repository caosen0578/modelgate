package com.pab.ficc.idp.modelgate.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.pab.ficc.idp.modelgate.cache.GatewayCacheManager;
import com.pab.ficc.idp.modelgate.common.BusinessException;
import com.pab.ficc.idp.modelgate.common.ErrorCode;
import com.pab.ficc.idp.modelgate.crypto.CryptoService;
import com.pab.ficc.idp.modelgate.entity.AppCredential;
import com.pab.ficc.idp.modelgate.mapper.AppCredentialMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class CredentialService {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final AppCredentialMapper credentialMapper;
    private final CryptoService cryptoService;
    private final GatewayCacheManager cacheManager;

    public List<AppCredential> listByAppId(String appId) {
        return credentialMapper.selectList(
                new LambdaQueryWrapper<AppCredential>().eq(AppCredential::getAppId, appId)
                        .orderByDesc(AppCredential::getCreatedAt));
    }

    public CreatedCredential create(String appId, AppCredential request) {
        AppCredential cred = new AppCredential();
        cred.setAppId(appId);
        cred.setCredName(request.getCredName());
        cred.setCredType(request.getCredType());
        cred.setExpiresAt(request.getExpiresAt());
        cred.setRemark(request.getRemark());
        cred.setEnabled(true);
        cred.setCreatedAt(LocalDateTime.now());
        cred.setUpdatedAt(LocalDateTime.now());

        String plainSecret;
        if ("ak_sk".equals(request.getCredType())) {
            String ak = "AK_" + appId.replace("-", "_") + "_" + randomHex(8);
            String sk = "SK_" + randomHex(32);
            cred.setAccessKey(ak);
            cred.setSecretHash(cryptoService.sha256Hex(sk));
            cred.setSecretEncrypted(cryptoService.encrypt(sk));
            plainSecret = sk;
        } else {
            String apiKey = "mg-" + appId + "-" + randomHex(24);
            cred.setAccessKey(apiKey.substring(apiKey.length() - 4));
            cred.setSecretHash(cryptoService.sha256Hex(apiKey));
            plainSecret = apiKey;
        }

        credentialMapper.insert(cred);
        cacheManager.evictCredentialCacheByAppId(appId);
        return new CreatedCredential(cred, plainSecret);
    }

    public Optional<AppCredential> findBySecretHash(String hash) {
        return cacheManager.getCredential(hash, () -> Optional.ofNullable(
                credentialMapper.selectOne(new LambdaQueryWrapper<AppCredential>()
                        .eq(AppCredential::getSecretHash, hash)
                        .eq(AppCredential::getEnabled, true))));
    }

    public Optional<AppCredential> findByAccessKey(String accessKey) {
        return Optional.ofNullable(credentialMapper.selectOne(
                new LambdaQueryWrapper<AppCredential>()
                        .eq(AppCredential::getAccessKey, accessKey)
                        .eq(AppCredential::getEnabled, true)));
    }

    public void enable(String appId, Long credId) {
        AppCredential cred = getCredentialByIdAndApp(appId, credId);
        credentialMapper.update(null, new LambdaUpdateWrapper<AppCredential>()
                .set(AppCredential::getEnabled, true)
                .set(AppCredential::getUpdatedAt, LocalDateTime.now())
                .eq(AppCredential::getId, credId));
        if (cred.getSecretHash() != null) cacheManager.evictCredentialCache(cred.getSecretHash());
    }

    public void disable(String appId, Long credId) {
        AppCredential cred = getCredentialByIdAndApp(appId, credId);
        credentialMapper.update(null, new LambdaUpdateWrapper<AppCredential>()
                .set(AppCredential::getEnabled, false)
                .set(AppCredential::getUpdatedAt, LocalDateTime.now())
                .eq(AppCredential::getId, credId));
        if (cred.getSecretHash() != null) cacheManager.evictCredentialCache(cred.getSecretHash());
    }

    public void delete(String appId, Long credId) {
        AppCredential cred = getCredentialByIdAndApp(appId, credId);
        credentialMapper.deleteById(credId);
        if (cred.getSecretHash() != null) cacheManager.evictCredentialCache(cred.getSecretHash());
    }

    private AppCredential getCredentialByIdAndApp(String appId, Long credId) {
        AppCredential cred = credentialMapper.selectById(credId);
        if (cred == null || !cred.getAppId().equals(appId)) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "credentialId: " + credId);
        }
        return cred;
    }

    private String randomHex(int bytes) {
        byte[] buf = new byte[bytes];
        SECURE_RANDOM.nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }

    public record CreatedCredential(AppCredential credential, String plainSecret) {}
}
