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
 * <p><b>为什么不是把 {@link HttpHeaders} 整体序列化</b>：入站请求必然带 Authorization/API Key，
 * 全量落库会把可直接调用网关的凭证长期明文保存在 {@code mg_request_payload}，风险不可接受。
 * 本类只读取显式白名单，并对已知凭证头设置不可绕过的硬拒绝名单。
 *
 * <p><b>热路径约束</b>：调用方只在报文记录已开启时调用本类；工作量与白名单数量成正比，
 * 不遍历全部请求头、不做 JSON 序列化。这里只复制少量字符串，JSON 序列化仍在
 * {@link PayloadService} 的定时消费线程完成。
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
    /** 防止误把长名单配置成“近似全量采集”，让每请求查找次数始终有硬上界。 */
    private static final int HARD_MAX_HEADER_COUNT = 16;
    private static final int HARD_MAX_HEADER_NAME_CHARS = 128;
    private static final int HARD_MAX_VALUE_CHARS = 16_384;
    private static final int HARD_MAX_TOTAL_CHARS = 32_768;

    private final List<String> allowedHeaders;
    private final int maxValueChars;
    private final int maxTotalChars;

    public RequestHeaderSnapshotter(
            @Value("${modelgate.payload-log.request-header-allowlist:X-User-Id,X-User-Name,X-User-Info}")
            String allowlist,
            @Value("${modelgate.payload-log.request-header-max-value-chars:8192}") int maxValueChars,
            @Value("${modelgate.payload-log.request-headers-max-total-chars:16384}") int maxTotalChars) {
        this.allowedHeaders = parseAllowlist(allowlist);
        this.maxValueChars = clamp("request-header-max-value-chars", maxValueChars,
                TRUNCATED_MARKER.length(), HARD_MAX_VALUE_CHARS);
        this.maxTotalChars = clamp("request-headers-max-total-chars", maxTotalChars,
                256, HARD_MAX_TOTAL_CHARS);
        log.info("[PayloadHeaders] 请求头采集白名单={}, 单值上限={}字符, 单条总上限={}字符",
                this.allowedHeaders, this.maxValueChars, this.maxTotalChars);
    }

    /**
     * 从请求头中复制白名单字段；返回值不再引用原 HttpHeaders，可安全放入异步队列。
     *
     * <p>同名多值用换行连接。模糊检索面向整段 JSON，因此用户信息位于值的中间也能命中。
     */
    public Map<String, String> capture(HttpHeaders headers) {
        if (headers == null || headers.isEmpty() || allowedHeaders.isEmpty()) {
            return Map.of();
        }

        Map<String, String> captured = new LinkedHashMap<>();
        int remaining = maxTotalChars;
        boolean truncated = false;

        for (String headerName : allowedHeaders) {
            List<String> values = headers.get(headerName);
            if (values == null || values.isEmpty()) {
                continue;
            }
            int available = Math.min(maxValueChars, remaining - headerName.length());
            if (available <= 0) {
                truncated = true;
                break;
            }
            String value = joinBounded(values, available);
            if (value == null) {
                continue;
            }
            if (value.endsWith(TRUNCATED_MARKER)) {
                truncated = true;
            }
            captured.put(headerName, value);
            remaining -= headerName.length() + value.length();
        }

        // 总上限导致后续白名单字段没机会采集时也要显式可见，不能静默伪装成“请求没带”。
        if (truncated) {
            captured.put("_modelgate_capture_truncated", "true");
        }
        return captured.isEmpty()
                ? Map.of()
                : Collections.unmodifiableMap(captured);
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
