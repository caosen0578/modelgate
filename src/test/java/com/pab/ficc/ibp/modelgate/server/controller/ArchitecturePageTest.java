package com.pab.ficc.ibp.modelgate.server.controller;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** 架构页离线结构与过滤器顺序守卫，避免图与代码再次静默漂移。 */
class ArchitecturePageTest {
    private static final Path PAGE = Path.of("src/main/resources/static/architecture.html");

    @Test
    void offlinePageHasFourDiagramsAndValidNavigation() throws Exception {
        String html = Files.readString(PAGE);
        Set<String> ids = new HashSet<>();
        var attributes = Pattern.compile("\\bid=\"([^\"]+)\"").matcher(html);
        while (attributes.find()) {
            assertTrue(ids.add(attributes.group(1)), "重复 HTML id: " + attributes.group(1));
        }
        for (String section : List.of("network", "pipeline", "recording", "replay")) {
            assertTrue(html.contains("<section id=\"" + section + "\""), section);
            assertTrue(html.contains("href=\"#" + section + "\""), section);
        }
        var anchors = Pattern.compile("href=\"#([^\"]+)\"").matcher(html);
        while (anchors.find()) {
            assertTrue(ids.contains(anchors.group(1)), "悬空锚点: " + anchors.group(1));
        }
        assertTrue(html.contains("name=\"viewport\""));
        assertTrue(html.contains("@media print"));
        assertTrue(html.contains("window.print()"));
        assertFalse(Pattern.compile("(?:src|href)=\"(?:https?:)?//", Pattern.CASE_INSENSITIVE)
                .matcher(html).find(), "内网架构页不能依赖外部资源");
    }

    @Test
    void displayedFilterOrdersMatchRuntimeConstants() throws Exception {
        String html = Files.readString(PAGE);
        Set<String> expected = Set.of("TraceFilter", "CredentialAuthFilter", "AppAccessControlFilter",
                "BodyParseFilter", "ModelAuthFilter", "ServiceAuthFilter", "QuotaFilter",
                "PayloadCaptureFilter", "ModelRouteFilter", "ServiceRouteFilter", "CircuitBreakerFilter");
        Set<String> found = new HashSet<>();
        var nodes = Pattern.compile("data-filter=\"(\\w+)\" data-order=\"(-?\\d+)\"(.*?)</article>",
                Pattern.DOTALL).matcher(html);
        while (nodes.find()) {
            String name = nodes.group(1);
            assertTrue(found.add(name), "重复的过滤器节点: " + name);
            var type = Class.forName("com.pab.ficc.ibp.modelgate.server.filter." + name);
            var order = type.getDeclaredField("ORDER");
            order.setAccessible(true);
            int actual = order.getInt(null);
            assertEquals(actual, Integer.parseInt(nodes.group(2)), name + " 顺序漂移");
            assertTrue(nodes.group(3).contains("class=\"order\">" + actual + "</span>"),
                    name + " 可见顺序与元数据不一致");
            assertTrue(nodes.group(3).contains(name), name + " 可见名称缺失");
        }
        assertEquals(expected, found);
        assertFalse(html.contains("RateLimitFilter"));
        assertFalse(html.contains(":8080"), "监听端口由 PORT_NO/server.port 决定，不应画死");
    }

    @Test
    void recordingAndReplayBoundariesAreExplicit() throws Exception {
        String html = Files.readString(PAGE);
        for (String contract : List.of("PORT_NO", "server.port", "spring.webflux.base-path",
                "/modelgate/payload/replay", "PendingWriteBuffer", "mg_request_payload", "mg_audit_log",
                "64 KiB", "64 MiB", "不读取、不缓存请求 body", "尚未脱敏",
                "不自动注入回放", "当前资源配置", "SSE 也聚合后返回", "不新增业务审计/报文记录",
                "没有自动幂等保障", "非流式服务开关的现有例外", "static-framework")) {
            assertTrue(html.contains(contract), "架构图缺少关键边界: " + contract);
        }
        assertTrue(html.contains("modelUpstream"), "模型目前共用熔断器，不能误画为按模型隔离");
        assertTrue(html.contains("svc:{serviceId}"));
    }
}
