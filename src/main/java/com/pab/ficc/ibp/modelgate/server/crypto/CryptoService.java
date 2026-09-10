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
import java.util.HexFormat;
import java.util.concurrent.TimeUnit;

/**
 * AES-256-GCM encryption/decryption for upstream secret keys.
 * Master key comes from application.yml's modelgate.crypto.key (32-byte hex = 64 chars,
 * literal value — no environment variable indirection; see spec 10 §2.1).
 *
 * <p><b>编码一律 hex（Base16），全项目不使用 base64</b>（内网合规要求，2026-09-10 改）。
 * 密文、主密钥、AKSK 签名、随机密钥材料四处此前都是 base64，现统一为小写 hex。
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

    /**
     * 全类统一的 hex 编解码器（小写输出）。
     *
     * <p>{@code HexFormat} 是 JDK 17 原生实现，内部就是查表，不走 {@code String.format}
     * 那套解析格式串 + Formatter + 装箱的开销——理由见 {@link #sha256Hex}。
     *
     * <p>{@code parseHex} <b>大小写通吃</b>（{@code 0-9a-fA-F} 都认），
     * 因此 AKSK 调用方用大写 hex 传签名也能正常验过，不必强制它们统一大小写。
     * 实例无状态、线程安全，全局复用一个。
     */
    private static final HexFormat HEX = HexFormat.of();

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

    public CryptoService(@Value("${modelgate.crypto.key:}") String hexKey) {
        // 单一固定文档，不再有环境变量兜底——application.yml 里这一项永远是字面量，
        // 为空只可能是运维显式把这一行清空（如某套环境暂不需要加密能力）。
        if (hexKey == null || hexKey.isBlank()) {
            // 未配置密钥：正常启动，但 encrypt/decrypt 调用时会抛异常（即注释所说的"不可用"）
            log.warn("[CryptoService] modelgate.crypto.key 未配置，上游凭证加密功能不可用，生产环境请务必设置");
            this.secretKey = null;
            return;
        }
        // 先判字符数再解码：hex 下「64 字符 ⟺ 32 字节」是恒等式（base64 因为 padding 做不到），
        // 所以长度写错时能直接报出"应为 64 位、实际 N 位"，不用等解码完再回头算字节数。
        String trimmed = hexKey.trim();
        if (trimmed.length() != 64) {
            throw new IllegalStateException(
                    "modelgate.crypto.key 必须是 64 位十六进制字符（对应 32 字节 AES-256 密钥），当前为 "
                    + trimmed.length() + " 位。生成命令：openssl rand -hex 32");
        }
        byte[] keyBytes;
        try {
            keyBytes = HEX.parseHex(trimmed);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "modelgate.crypto.key 不是合法的十六进制字符串（只允许 0-9a-fA-F）。"
                    + "生成命令：openssl rand -hex 32", e);
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
            // 必须与 decrypt 的 UTF-8 对称。此前 encrypt 用平台默认字符集、
            // decrypt 也用默认，勉强自洽；现在 decrypt 已固定 UTF-8，这里不跟上就会
            // 在非 UTF-8 平台上出现"加密再解密拿到乱码"。密钥材料是纯 ASCII 时两者无差别，
            // 但不能把正确性寄托在"输入恰好是 ASCII"上。
            byte[] cipherText = cipher.doFinal(plaintext.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            ByteBuffer buf = ByteBuffer.allocate(iv.length + cipherText.length);
            buf.put(iv);
            buf.put(cipherText);
            return HEX.formatHex(buf.array());
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
    public String decrypt(String hexCiphertext) {
        if (hexCiphertext == null || hexCiphertext.isBlank()) return null;
        if (secretKey == null) throw new IllegalStateException("modelgate.crypto.key 未配置，无法解密凭证");

        byte[] decoded;
        try {
            decoded = HEX.parseHex(hexCiphertext);
        } catch (IllegalArgumentException e) {
            // 2026-09-10 起密文一律 hex。若这条报错出现在刚升级完的环境上，
            // 大概率是库里还留着升级前写入的 base64 密文——那批数据解不回来，只能重新生成凭证。
            throw new CryptoException("凭证密文不是合法的十六进制串，数据已损坏或为 hex 改造前的旧格式，需重新生成该凭证", e);
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
     * 现在直接用 {@link #HEX}（{@code HexFormat}，JDK 内部同样是查表），
     * 与本类其余 hex 编解码共用一套，不再自带一份手写码表。
     *
     * <p><b>字符集显式固定为 UTF-8</b>（2026-08-19 改）。此前用的是
     * {@code input.getBytes()} 即平台默认字符集——Linux 通常 UTF-8、Windows 是 GBK，
     * <b>同一个 key 在两台机器上会算出不同的哈希</b>，表现为"凭证在某台机器上突然认不出来"，
     * 而且这种故障只在密钥含非 ASCII 字符时才出现，极难复现和定位。
     *
     * <p><b>兼容性影响</b>：库中已有的 {@code secret_hash} 是用旧字符集算出来的。
     * 只有当被哈希的字符串含<b>非 ASCII 字符</b>时新旧结果才会不同——
     * 密钥材料本身是 hex（纯 ASCII；2026-09-10 前是 URL-safe Base64，同样纯 ASCII），
     * 唯一的暴露面是 api_key 里拼接的 appId
     * （{@code "mg-" + appId + "-" + randomHex(24)}）。
     * 因此<b>只有"appId 含非 ASCII 字符"的 api_key 类型凭证会失效</b>，需重新生成。
     * 定位这类凭证的 SQL 见 {@code sql/upgrade_20260819_sha256_utf8_migration.sql}。
     */
    public String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HEX.formatHex(hash);
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
    public String decryptCached(String hexCiphertext) {
        if (hexCiphertext == null || hexCiphertext.isBlank()) return null;
        // 解密失败会抛异常，Caffeine 不会缓存异常，下次仍会重试——正是想要的行为
        return decryptCache.get(hexCiphertext, this::decrypt);
    }

    public boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return MessageDigest.isEqual(
                    md.digest(a.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                    md.digest(b.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            return a.equals(b);
        }
    }
}
