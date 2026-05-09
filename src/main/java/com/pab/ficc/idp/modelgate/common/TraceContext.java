package com.pab.ficc.idp.modelgate.common;

import org.slf4j.MDC;

import java.util.UUID;

/**
 * 链路追踪 ID 工具
 * 在 MDC 中存储 traceId，随日志自动打印，也写入响应头 X-Trace-Id
 */
public final class TraceContext {

    public static final String TRACE_ID_KEY = "traceId";
    public static final String HEADER_TRACE_ID = "X-Trace-Id";

    private TraceContext() {}

    public static String generate() {
        String traceId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        MDC.put(TRACE_ID_KEY, traceId);
        return traceId;
    }

    public static String get() {
        return MDC.get(TRACE_ID_KEY);
    }

    public static void set(String traceId) {
        MDC.put(TRACE_ID_KEY, traceId);
    }

    public static void clear() {
        MDC.clear();
    }
}
