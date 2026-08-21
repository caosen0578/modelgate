package com.pab.ficc.ibp.modelgate.server.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 对需要留档的全部请求头做有界快照。
 *
 * <p><b>按运维排障要求保留原始信息</b>：不做白名单或敏感字段排除，Authorization、Cookie、
 * API Key、Token、JWT、签名等入站 Header 都会进入 {@code mg_request_payload.request_headers}。
 * 这些值可能具备直接调用权限，生产环境必须依赖管理面访问控制、数据库权限与报文保留期保护。
 *
 * <p><b>热路径约束</b>：调用方只在报文记录已开启时调用本类；请求线程只单次遍历 Header 并复制
 * 有界字符串，不做 JSON 序列化。单值与总字符保险丝防止异常 Header 放大堆占用；JSON 序列化仍在
 * {@link PayloadService} 的定时消费线程完成。
 */
@Slf4j
@Component
public class RequestHeaderSnapshotter {

    private static final String TRUNCATED_MARKER = "…[truncated]";
    private static final int HARD_MAX_VALUE_CHARS = 16_384;
    private static final int HARD_MAX_TOTAL_CHARS = 32_768;

    private final int maxValueChars;
    private final int maxTotalChars;

    public RequestHeaderSnapshotter(
            @Value("${modelgate.payload-log.request-header-max-value-chars:8192}") int maxValueChars,
            @Value("${modelgate.payload-log.request-headers-max-total-chars:16384}") int maxTotalChars) {
        this.maxValueChars = clamp("request-header-max-value-chars", maxValueChars,
                TRUNCATED_MARKER.length(), HARD_MAX_VALUE_CHARS);
        this.maxTotalChars = clamp("request-headers-max-total-chars", maxTotalChars,
                256, HARD_MAX_TOTAL_CHARS);
        log.warn("[PayloadHeaders] 采集模式=全部请求头（包含凭证字段）, 单值上限={}字符, 单条总上限={}字符",
                this.maxValueChars, this.maxTotalChars);
    }

    /**
     * 复制全部入站字段；返回值不再引用原 HttpHeaders，可安全放入异步队列。
     *
     * <p>同名多值用换行连接。模糊检索面向整段 JSON，因此用户信息位于值的中间也能命中。
     */
    public Map<String, String> capture(HttpHeaders headers) {
        if (headers == null || headers.isEmpty()) {
            return Map.of();
        }

        Map<String, String> captured = new LinkedHashMap<>();
        int remaining = maxTotalChars;
        boolean truncated = false;
        // HttpHeaders 已由 WebFlux/Netty 完成名称合法性与协议层大小校验；这里只单次复制，不再筛选字段。
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            String headerName = entry.getKey();
            if (headerName == null) continue;
            int available = Math.min(maxValueChars, remaining - headerName.length());
            if (available <= 0) {
                truncated = true;
                break;
            }
            String value = joinBounded(entry.getValue(), available);
            if (value == null) continue;
            if (value.endsWith(TRUNCATED_MARKER)) truncated = true;
            captured.put(headerName, value);
            remaining -= headerName.length() + value.length();
        }
        if (truncated) captured.put("_modelgate_capture_truncated", "true");
        return captured.isEmpty() ? Map.of() : Collections.unmodifiableMap(captured);
    }

    public int maxValueChars() {
        return maxValueChars;
    }

    public int maxTotalChars() {
        return maxTotalChars;
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
        if (values == null || values.isEmpty()) return null;
        StringBuilder out = new StringBuilder(Math.min(maxChars, 256));
        boolean clipped = false;
        boolean sawValue = false;
        for (String value : values) {
            if (value == null) continue;
            int separator = sawValue ? 1 : 0;
            int remaining = maxChars - out.length() - separator;
            if (remaining <= 0) {
                clipped = true;
                break;
            }
            if (separator > 0) out.append('\n');
            sawValue = true;
            if (value.length() <= remaining) {
                out.append(value);
            } else {
                appendTruncated(out, value, remaining);
                clipped = true;
                break;
            }
        }
        // 空字符串也是合法 Header 值，必须作为 "" 留档；只有全是 null 才视为无值。
        if (!sawValue) return null;
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
