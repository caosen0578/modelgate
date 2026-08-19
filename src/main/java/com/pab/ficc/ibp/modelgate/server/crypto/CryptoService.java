package com.pab.ficc.ibp.modelgate.server.crypto;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
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
import java.util.concurrent.TimeUnit;

/**
 * AES-256-GCM encryption/decryption for upstream secret keys.
 * Master key comes from application.yml's modelgate.crypto.key (32-byte base64,
 * literal value — no environment variable indirection; see spec 10 §2.1).
 */
@Slf4j
@Service
public class CryptoService {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH = 128;

    private final SecretKey secretKey;

    /**
     * IV 生成用的随机源。
     *
     * <p>{@code new SecureRandom()} <b>每次都会重新做一轮播种</b>（Linux 上可能读 /dev/random），
     * 开销远大于取几个随机字节。SecureRandom 实例本身是线程安全的，全局复用一个即可。
     */
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    /** 十六进制查表，替代 String.format("%02x")——理由见 {@link #sha256Hex} */
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    /**
     * 密文 → 明文 的记忆化缓存。
     *
     * <p><b>为什么需要</b>：AKSK 验签在<b>每个请求</b>上都要拿到明文 SK，
     * 原先每请求做一次完整的 AES-GCM 解密。密文是不可变的，同一段密文的解密结果永远相同，
     * 属于纯函数，天然可缓存。
     *
     * <p><b>安全取舍</b>：这会让明文 SK 在堆里驻留更久（此前也在堆里，只是转瞬即逝）。
     * 因此刻意限制得很紧：{@code maximumSize=1000}（凭证数量级远小于此）+
     * {@code expireAfterAccess=10 分钟}（不再被使用的凭证很快就被清出内存）。
     * 用 {@code expireAfterAccess} 而非 {@code expireAfterWrite}：活跃凭证不必反复解密，
     * 而闲置凭证会自然过期。
     *
     * <p>缓存<b>不影响凭证的启用/禁用实时性</b>：那由 {@code credentialCache} 独立控制，
     * 这里只缓存"这段密文解出来是什么"，与凭证是否有效无关。
     */
    private final Cache<String, String> decryptCache = Caffeine.newBuilder()
            .expireAfterAccess(10, TimeUnit.MINUTES)
            .maximumSize(1_000)
            .build();

    public CryptoService(@Value("${modelgate.crypto.key:}") String base64Key) {
        // 单一固定文档，不再有环境变量兜底——application.yml 里这一项永远是字面量，
        // 为空只可能是运维显式把这一行清空（如某套环境暂不需要加密能力）。
        if (base64Key == null || base64Key.isBlank()) {
            // 未配置密钥：正常启动，但 encrypt/decrypt 调用时会抛异常（即注释所说的"不可用"）
            log.warn("[CryptoService] modelgate.crypto.key 未配置，上游凭证加密功能不可用，生产环境请务必设置");
            this.secretKey = null;
            return;
        }
        byte[] keyBytes;
        try {
            keyBytes = Base64.getDecoder().decode(base64Key);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("modelgate.crypto.key 不是合法的 Base64 字符串", e);
        }
        if (keyBytes.length != 32) {
            throw new IllegalStateException(
                    "modelgate.crypto.key 解码后必须恰好为 32 字节，当前为 " + keyBytes.length + " 字节。" +
                    "生成命令：openssl rand -base64 32");
        }
        this.secretKey = new SecretKeySpec(keyBytes, "AES");
    }

    public String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isBlank()) return null;
        if (secretKey == null) throw new IllegalStateException("modelgate.crypto.key 未配置，无法加密凭证");
        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            SECURE_RANDOM.nextBytes(iv);
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

    /**
     * 解密。
     *
     * <p><b>失败原因必须说清楚</b>。此前一律抛 {@code RuntimeException("Decryption failed")}，
     * 前端只看到一个 500 「internal server error」，运维完全看不出到底是
     * 「密钥配错了」还是「这条密文本身是坏的」——这两者的处理方式截然不同：
     * 前者要改 {@code modelgate.crypto.key}，后者要重新生成凭证。
     * 实测就踩过一次：本地联调密钥与库中凭证的加密密钥不匹配，
     * 排查时只能靠翻堆栈才知道是 AEADBadTagException。
     */
    public String decrypt(String base64Ciphertext) {
        if (base64Ciphertext == null || base64Ciphertext.isBlank()) return null;
        if (secretKey == null) throw new IllegalStateException("modelgate.crypto.key 未配置，无法解密凭证");

        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(base64Ciphertext);
        } catch (IllegalArgumentException e) {
            throw new CryptoException("凭证密文不是合法的 Base64，数据已损坏，需重新生成该凭证", e);
        }
        if (decoded.length <= GCM_IV_LENGTH) {
            throw new CryptoException("凭证密文长度不足（" + decoded.length + " 字节，至少需要 "
                    + (GCM_IV_LENGTH + 1) + " 字节），数据已损坏，需重新生成该凭证", null);
        }
        try {
            ByteBuffer buf = ByteBuffer.wrap(decoded);
            byte[] iv = new byte[GCM_IV_LENGTH];
            buf.get(iv);
            byte[] cipherText = new byte[buf.remaining()];
            buf.get(cipherText);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            return new String(cipher.doFinal(cipherText), java.nio.charset.StandardCharsets.UTF_8);
        } catch (javax.crypto.AEADBadTagException e) {
            // GCM 认证标签对不上。密文结构没问题，是**解不开**——
            // 绝大多数情况是当前 modelgate.crypto.key 与加密时用的那把不是同一把。
            throw new CryptoException(
                    "凭证解密失败：GCM 认证标签校验不通过。最可能的原因是当前 modelgate.crypto.key "
                    + "与该凭证加密时使用的密钥**不是同一把**（例如换了环境、或密钥被改过）；"
                    + "其次是密文在存储/传输中被篡改或截断。"
                    + "处理方式：确认本环境 application.yml 的 modelgate.crypto.key 是否为加密该数据时的那把；"
                    + "若密钥确已丢失，只能重新生成凭证（旧密文无法找回）", e);
        } catch (Exception e) {
            throw new CryptoException("凭证解密失败：" + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : " - " + e.getMessage()), e);
        }
    }

    /**
     * 解密失败专用异常。
     *
     * <p>独立类型是为了让上层能<b>区分对待</b>：这类失败属于「配置/数据问题」，
     * 应当回一个说明得清清楚楚的 4xx，而不是混进未捕获异常里变成 500 「internal server error」。
     */
    public static class CryptoException extends RuntimeException {
        public CryptoException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * SHA-256 十六进制串。
     *
     * <p><b>这是热路径</b>：每个 api_key 请求都要算一次（CredentialAuthFilter 用它查凭证）。
     * 原实现对 32 个字节各调一次 {@code String.format("%02x", b)}——
     * String.format 要解析格式串、走 Formatter、装箱参数，单次就在微秒级，
     * 32 次叠起来是每请求几十微秒，纯属白烧。改成查表后是几十纳秒的事。
     *
     * <p>⚠️ <b>{@code input.getBytes()} 用的是平台默认字符集，这里刻意<u>不</u>改成显式 UTF-8。</b>
     * 已知这是个隐患：不同平台默认字符集不同（Linux 通常 UTF-8、Windows 是 GBK），
     * 同一个 key 在两台机器上会算出<b>不同的哈希</b>。
     * 但<b>现在改会直接炸掉存量凭证</b>——库里 {@code secret_hash} 是当初用旧字符集算出来的，
     * 换算法就再也匹配不上，表现为"凭证突然全部认证失败"，且无法回滚（明文已不可得）。
     *
     * <p>实际风险有多大：生成的密钥材料本身是 URL-safe Base64（纯 ASCII），
     * 两种字符集编码结果完全一致；<b>唯一的暴露面是 api_key 里拼接的 appId</b>
     * （{@code "mg-" + appId + "-" + randomHex(24)}），而 appId 由用户创建系统时输入。
     * 当前库中 appId 均为数字，不受影响。
     *
     * <p><b>要修的话必须配套做</b>：先扫描所有含非 ASCII appId 的凭证，
     * 对它们重新生成密钥（旧的注定失效），再统一切到 UTF-8。
     * 单独改这一行等于埋雷。
     */
    public String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes());
            char[] out = new char[hash.length * 2];
            for (int i = 0; i < hash.length; i++) {
                int v = hash[i] & 0xFF;
                out[i * 2] = HEX[v >>> 4];
                out[i * 2 + 1] = HEX[v & 0x0F];
            }
            return new String(out);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    /**
     * 带记忆化的解密，供<b>热路径</b>使用（AKSK 验签每请求都要明文 SK）。
     *
     * <p>解密是纯函数：同一段密文永远解出同一个明文，缓存不会带来一致性问题。
     * 缓存边界与安全取舍见 {@link #decryptCache}。
     *
     * <p>管理面的「查看密钥」等低频场景仍应直接用 {@link #decrypt}，
     * 不必也不该把一次性的查看塞进缓存。
     */
    public String decryptCached(String base64Ciphertext) {
        if (base64Ciphertext == null || base64Ciphertext.isBlank()) return null;
        // 解密失败会抛异常，Caffeine 不会缓存异常，下次仍会重试——正是想要的行为
        return decryptCache.get(base64Ciphertext, this::decrypt);
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
