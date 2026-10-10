package com.pab.ficc.ibp.modelgate.server.common;

import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

/** 部署路径与应用内路径的唯一边界；不读取或重建请求体。 */
@Component
public final class ApplicationPaths {
    private final String basePath;

    public ApplicationPaths(@Value("${spring.webflux.base-path:}") String basePath) {
        this.basePath = basePath == null || basePath.equals("/") ? "" : basePath.replaceAll("/+$", "");
    }

    /** 与旧 URI.getPath 一样返回 decoded 路径，但移除框架确认的 contextPath。 */
    public static String path(ServerHttpRequest request) { return uri(request).getPath(); }

    /** URI 的 raw 编码/query 不变；部署文根不传给供应商或通用服务上游。 */
    public static URI uri(ServerHttpRequest request) {
        if (request.getPath().contextPath().value().isEmpty()) return request.getURI();
        return UriComponentsBuilder.fromUri(request.getURI())
            .replacePath(request.getPath().pathWithinApplication().value()).build(true).toUri();
    }

    public String publicPath(String applicationPath) { return basePath + applicationPath; }

    /** 留档有新路径也有旧路径；只剥精确文根段，不能误剥 /modelgateX。 */
    public String applicationPath(String storedPath) {
        if (storedPath == null || basePath.isEmpty()) return storedPath;
        if (storedPath.equals(basePath)) return "/";
        return storedPath.startsWith(basePath + "/") ? storedPath.substring(basePath.length()) : storedPath;
    }
}
