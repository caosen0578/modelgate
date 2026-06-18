package com.pab.ficc.ibp.modelgate.server.crypto;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256-GCM encryption/decryption for upstream secret keys.
 * Master key is read from environment variable MODELGATE_CRYPTO_KEY (32-byte base64).
 */
@Slf4j
@Service
public class



























































































































































































































































































































































































































CryptoService {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH = 128;

    private final SecretKey secretKey;

    public CryptoService(@Value("${modelgate.crypto.key:}") String base64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            base64Key = System.getenv("MODELGATE_CRYPTO_KEY");
        }
        if (base64Key == null || base64Key.isBlank()) {
            // 未配置密钥：正常启动，但 encrypt/decrypt 调用时会抛异常（即注释所说的"不可用"）
            log.warn("[CryptoService] MODELGATE_CRYPTO_KEY 未配置，上游凭证加密功能不可用，生产环境请务必设置");
            this.secretKey = null;
            return;
        }
        byte[] keyBytes;
        try {
            keyBytes = Base64.getDecoder().decode(base64Key);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("MODELGATE_CRYPTO_KEY 不是合法的 Base64 字符串", e);
        }
        if (keyBytes.length != 32) {
            throw new IllegalStateException(
                    "MODELGATE_CRYPTO_KEY 解码后必须恰好为 32 字节，当前为 " + keyBytes.length + " 字节。" +
                    "生成命令：openssl rand -base64 32");
        }
        this.secretKey = new SecretKeySpec(keyBytes, "AES");
    }

    public String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isBlank()) return null;
        if (secretKey == null) throw new IllegalStateException("MODELGATE_CRYPTO_KEY 未配置，无法加密凭证");
        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            new SecureRandom().nextBytes(iv);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            byte[] cipherText = cipher.doFinal(plaintext.getBytes());
            ByteBuffer buf = ByteBuffer.allocate(iv.length + cipherText.length);
            buf.put(iv);
            buf.put(cipherText);
            return Base64.getEncoder().encodeToString(buf.array());
        } catch (Exception e) {
            throw new RuntimeException("Encryption failed", e);
        }
    }

    public String decrypt(String base64Ciphertext) {
        if (base64Ciphertext == null || base64Ciphertext.isBlank()) return null;
        if (secretKey == null) throw new IllegalStateException("MODELGATE_CRYPTO_KEY 未配置，无法解密凭证");
        try {
            byte[] decoded = Base64.getDecoder().decode(base64Ciphertext);
            ByteBuffer buf = ByteBuffer.wrap(decoded);
            byte[] iv = new byte[GCM_IV_LENGTH];
            buf.get(iv);
            byte[] cipherText = new byte[buf.remaining()];
            buf.get(cipherText);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            return new String(cipher.doFinal(cipherText));
        } catch (Exception e) {
            throw new RuntimeException("Decryption failed", e);
        }
    }

    public String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes());
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    public boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return MessageDigest.isEqual(md.digest(a.getBytes()), md.digest(b.getBytes()));
        } catch (NoSuchAlgorithmException e) {
            return a.equals(b);
        }
    }
}
