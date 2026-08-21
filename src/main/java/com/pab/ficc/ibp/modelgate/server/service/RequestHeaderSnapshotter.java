package com.pab.ficc.ibp.modelgate.server.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 对需要留档的请求头做安全、有界快照。
 *
 * <p><b>为什么不是把 {@link HttpHeaders} 整体直接序列化</b>：入站请求必然带 Authorization/API Key，
 * 原样落库会把可直接调用网关的凭证长期明文保存在 {@code mg_request_payload}，风险不可接受。
 * 未配置白名单时本类采集全部<b>非敏感</b> Header；显式配置后只采集名单内字段。
 * 两种模式都先执行不可绕过的凭证头硬拒绝规则。
 *
 * <p><b>热路径约束</b>：调用方只在报文记录已开启时调用本类。白名单模式只做定点查找；
 * 默认模式单次遍历 Header，且字段数、单值与总字符数都有硬上限。这里只复制有界字符串，
 * 不做 JSON 序列化；JSON 序列化仍在 {@link PayloadService} 的定时消费线程完成。
 */
@Slf4j
@Component
public class RequestHeaderSnapshotter {

    private static final Pattern VALID_HEADER_NAME =
            Pattern.compile("[!#$%&'*+.^_`|~0-9A-Za-z-]+");

    /** 即使误加进白名单也绝不落库，避免配置失误变成凭证泄露。 */
    private static final Set<String> FORBIDDEN_HEADERS = Set.of(
            "authorization", "proxy-authorization", "cookie", "set-cookie",
            "x-api-key", "api-key", "x-access-key", "x-secret-key",
            "x-me2ai-accesskey", "x-me2ai-signature"
    );

    private static final String TRUNCATED_MARKER = "…[truncated]";
    /** 默认全量模式也必须有硬上界；64 项足以覆盖正常 HTTP 请求，同时挡住异常 Header 风暴。 */
    private static final int HARD_MAX_HEADER_COUNT = 64;
    private static final int HARD_MAX_HEADER_NAME_CHARS = 128;
    private static final int HARD_MAX_VALUE_CHARS = 16_384;
    private static final int HARD_MAX_TOTAL_CHARS = 32_768;

    private final boolean captureAllSafeHeaders;
    private final List<String> allowedHeaders;
    private final int maxValueChars;
    private final int maxTotalChars;

    public RequestHeaderSnapshotter(
            @Value("${modelgate.payload-log.request-header-allowlist:}")
            String allowlist,
            @Value("${modelgate.payload-log.request-header-max-value-chars:8192}") int maxValueChars,
            @Value("${modelgate.payload-log.request-headers-max-total-chars:16384}") int maxTotalChars) {
        this.captureAllSafeHeaders = allowlist == null || allowlist.isBlank();
        this.allowedHeaders = captureAllSafeHeaders ? List.of() : parseAllowlist(allowlist);
        this.maxValueChars = clamp("request-header-max-value-chars", maxValueChars,
                TRUNCATED_MARKER.length(), HARD_MAX_VALUE_CHARS);
        this.maxTotalChars = clamp("request-headers-max-total-chars", maxTotalChars,
                256, HARD_MAX_TOTAL_CHARS);
        if (captureAllSafeHeaders) {
            log.info("[PayloadHeaders] 采集模式=全部非敏感请求头, 字段上限={}, 单值上限={}字符, 单条总上限={}字符",
                    HARD_MAX_HEADER_COUNT, this.maxValueChars, this.maxTotalChars);
        } else {
            log.info("[PayloadHeaders] 采集模式=显式白名单, 有效白名单={}, 单值上限={}字符, 单条总上限={}字符",
                    this.allowedHeaders, this.maxValueChars, this.maxTotalChars);
            if (this.allowedHeaders.isEmpty()) {
                log.warn("[PayloadHeaders] 已配置 request-header-allowlist，但没有任何有效字段；"
                        + "当前不会记录请求头。删除或留空该配置可采集全部非敏感请求头");
            }
        }
    }

    /**
     * 复制需要留档的安全字段；返回值不再引用原 HttpHeaders，可安全放入异步队列。
     *
     * <p>同名多值用换行连接。模糊检索面向整段 JSON，因此用户信息位于值的中间也能命中。
     */
    public Map<String, String> capture(HttpHeaders headers) {
        if (headers == null || headers.isEmpty()
                || (!captureAllSafeHeaders && allowedHeaders.isEmpty())) {
            return Map.of();
        }

        CaptureBuffer buffer = new CaptureBuffer(maxValueChars, maxTotalChars);
        if (captureAllSafeHeaders) {
            // 默认模式只遍历一次，并在 64 项/总字符上限处停止；凭证类 Header 始终先过滤。
            for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
                String headerName = entry.getKey();
                if (headerName == null || isForbidden(headerName.toLowerCase(Locale.ROOT))) {
                    continue;
                }
                if (headerName.length() > HARD_MAX_HEADER_NAME_CHARS
                        || !VALID_HEADER_NAME.matcher(headerName).matches()) {
                    buffer.markTruncated();
                    continue;
                }
                if (!buffer.add(headerName, entry.getValue())) {
                    break;
                }
            }
        } else {
            // 白名单模式不遍历入站 Header，只按配置名称做大小写不敏感的定点查找。
            for (String headerName : allowedHeaders) {
                if (!buffer.add(headerName, headers.get(headerName))) {
                    break;
                }
            }
        }
        return buffer.finish();
    }

    public String captureMode() {
        return captureAllSafeHeaders ? "all_safe" : "allowlist";
    }

    public boolean capturesAllSafeHeaders() {
        return captureAllSafeHeaders;
    }

    public List<String> allowedHeaders() {
        return allowedHeaders;
    }

    public int maxValueChars() {
        return maxValueChars;
    }

    public int maxTotalChars() {
        return maxTotalChars;
    }

    public int maxHeaderCount() {
        return HARD_MAX_HEADER_COUNT;
    }

    /** 单次快照的有界累加器；截断必须显式写入，不能伪装成“请求没有这个 Header”。 */
    private static final class CaptureBuffer {
        private final Map<String, String> captured = new LinkedHashMap<>();
        private final int maxValueChars;
        private int remaining;
        private boolean truncated;

        private CaptureBuffer(int maxValueChars, int maxTotalChars) {
            this.maxValueChars = maxValueChars;
            this.remaining = maxTotalChars;
        }

        private boolean add(String headerName, List<String> values) {
            if (values == null || values.isEmpty()) return true;
            if (captured.size() >= HARD_MAX_HEADER_COUNT) {
                truncated = true;
                return false;
            }
            int available = Math.min(maxValueChars, remaining - headerName.length());
            if (available <= 0) {
                truncated = true;
                return false;
            }
            String value = joinBounded(values, available);
            if (value == null) return true;
            if (value.endsWith(TRUNCATED_MARKER)) truncated = true;
            captured.put(headerName, value);
            remaining -= headerName.length() + value.length();
            return true;
        }

        private void markTruncated() {
            truncated = true;
        }

        private Map<String, String> finish() {
            if (truncated) captured.put("_modelgate_capture_truncated", "true");
            return captured.isEmpty()
                    ? Map.of()
                    : Collections.unmodifiableMap(captured);
        }
    }

    private static List<String> parseAllowlist(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        Set<String> seenLowerCase = new LinkedHashSet<>();
        List<String> result = new ArrayList<>();
        for (String part : raw.split(",")) {
            String name = part.trim();
            if (name.isEmpty()) continue;
            String lower = name.toLowerCase(Locale.ROOT);
            if (name.length() > HARD_MAX_HEADER_NAME_CHARS) {
                log.warn("[PayloadHeaders] 忽略过长请求头名称（{}>{}）: {}...",
                        name.length(), HARD_MAX_HEADER_NAME_CHARS,
                        name.substring(0, HARD_MAX_HEADER_NAME_CHARS));
                continue;
            }
            if (!VALID_HEADER_NAME.matcher(name).matches()) {
                log.warn("[PayloadHeaders] 忽略非法请求头名称: {}", name);
                continue;
            }
            if (isForbidden(lower)) {
                log.warn("[PayloadHeaders] 安全拒绝采集凭证类请求头: {}", name);
                continue;
            }
            if (seenLowerCase.add(lower)) {
                if (result.size() >= HARD_MAX_HEADER_COUNT) {
                    log.warn("[PayloadHeaders] 请求头白名单超过硬上限 {}，后续字段已忽略；"
                            + "请只保留排障需要的用户标识字段", HARD_MAX_HEADER_COUNT);
                    break;
                }
                result.add(name);
            }
        }
        return List.copyOf(result);
    }

    private static boolean isForbidden(String lowerName) {
        return FORBIDDEN_HEADERS.contains(lowerName)
                || lowerName.contains("authorization")
                || lowerName.contains("cookie")
                || lowerName.contains("api-key")
                || lowerName.contains("apikey")
                || lowerName.contains("access-key")
                || lowerName.contains("accesskey")
                || lowerName.contains("auth-key")
                || lowerName.contains("credential")
                || lowerName.contains("secret")
                || lowerName.contains("password")
                || lowerName.contains("signature")
                || lowerName.contains("session-id")
                || lowerName.equals("jwt")
                || lowerName.endsWith("-jwt")
                || lowerName.equals("token")
                || lowerName.endsWith("-token");
    }

    private static int clamp(String name, int value, int min, int max) {
        int effective = Math.max(min, Math.min(max, value));
        if (effective != value) {
            log.warn("[PayloadHeaders] {}={} 越界，已收敛为 {}（允许 {}..{}）",
                    name, value, effective, min, max);
        }
        return effective;
    }

    private static String joinBounded(List<String> values, int maxChars) {
        StringBuilder out = new StringBuilder(Math.min(maxChars, 256));
        boolean clipped = false;
        for (String value : values) {
            if (value == null) continue;
            int separator = out.isEmpty() ? 0 : 1;
            int remaining = maxChars - out.length() - separator;
            if (remaining <= 0) {
                clipped = true;
                break;
            }
            if (separator > 0) out.append('\n');
            if (value.length() <= remaining) {
                out.append(value);
            } else {
                appendTruncated(out, value, remaining);
                clipped = true;
                break;
            }
        }
        if (out.isEmpty()) return null;
        // 避免 out.toString() 产生一次与 Header 长度相同的临时字符串；截断虽是低频分支，
        // 仍保持请求热路径只有最终快照这一次 String 分配。
        if (clipped && !endsWith(out, TRUNCATED_MARKER)) {
            appendMarker(out, maxChars);
        }
        return out.toString();
    }

    private static boolean endsWith(StringBuilder value, String suffix) {
        if (value.length() < suffix.length()) return false;
        int offset = value.length() - suffix.length();
        for (int i = 0; i < suffix.length(); i++) {
            if (value.charAt(offset + i) != suffix.charAt(i)) return false;
        }
        return true;
    }

    private static void appendTruncated(StringBuilder out, String value, int remaining) {
        int contentChars = Math.max(0, remaining - TRUNCATED_MARKER.length());
        out.append(value, 0, Math.min(value.length(), contentChars));
        if (remaining >= TRUNCATED_MARKER.length()) {
            out.append(TRUNCATED_MARKER);
        }
    }

    private static void appendMarker(StringBuilder out, int maxChars) {
        if (maxChars < TRUNCATED_MARKER.length()) return;
        int keep = Math.max(0, maxChars - TRUNCATED_MARKER.length());
        if (out.length() > keep) out.setLength(keep);
        out.append(TRUNCATED_MARKER);
    }
}
