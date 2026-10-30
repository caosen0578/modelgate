package com.pab.ficc.ibp.modelgate.server.controller;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** 汇报简版独立交付，保留详细版入口，并防止简化成不真实的能力描述。 */
class ArchitectureOverviewPageTest {
    @Test
    void overviewHasAccessibleDiagramsAndValidLocalReferences() throws Exception {
        String html = Files.readString(Path.of("src/main/resources/static/architecture-overview.html"));
        Set<String> ids = new HashSet<>();
        var attributes = Pattern.compile("\\bid=\"([^\"]+)\"").matcher(html);
        while (attributes.find()) assertTrue(ids.add(attributes.group(1)), "重复 id: " + attributes.group(1));
        var links = Pattern.compile("(?:href=\"#|url\\(#)([\\w-]+)").matcher(html);
        while (links.find()) assertTrue(ids.contains(links.group(1)), "悬空引用: " + links.group(1));
        assertEquals(3, Pattern.compile("class=\"diagram\"").matcher(html).results().count());
        assertEquals(3, Pattern.compile("role=\"img\"").matcher(html).results().count());
        assertTrue(html.contains("<h2 id=\"overview-heading\">模型与服务网关</h2>"));
        assertTrue(html.contains("<h2 id=\"recording-heading\">流量录制</h2>"));
        assertTrue(html.contains("<h2 id=\"replay-heading\">流量回放</h2>"));
        assertTrue(html.contains("href=\"architecture.html\""));
        assertTrue(Files.exists(Path.of("src/main/resources/static/architecture.html")));
        assertFalse(Pattern.compile("(?:src|href)=\"(?:https?:)?//", Pattern.CASE_INSENSITIVE).matcher(html).find());
        assertTrue(html.contains("@media print"));
        assertTrue(html.contains("window.print()"));
    }

    @Test
    void executiveViewKeepsOnlyBusinessConceptsAndImportantCaveats() throws Exception {
        String html = Files.readString(Path.of("src/main/resources/static/architecture-overview.html"));
        for (String term : List.of("一个入口", "权限校验", "用量控制", "旁路复制", "异步保存",
                "不是播放旧响应", "当前配置", "可能产生成本或重复业务操作", "部分请求内容或响应可能未完整记录")) {
            assertTrue(html.contains(term), "简图缺少必要概念: " + term);
        }
        for (String technical : List.of("TraceFilter", "PayloadService", "ProviderAdapter", "DataBuffer", "mg_request_payload", "order=-")) {
            assertFalse(html.contains(technical), "汇报简版不应堆积代码组件名: " + technical);
        }
    }
}
