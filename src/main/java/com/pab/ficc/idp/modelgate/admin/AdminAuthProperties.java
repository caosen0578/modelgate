package com.pab.ficc.idp.modelgate.admin;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Admin 鉴权配置属性（由 Apollo 热更新驱动）
 */
@Data
@Component
@ConfigurationProperties(prefix = "modelgate.admin")
public class AdminAuthProperties {

    /** Admin Token 列表 */
    private List<TokenEntry> tokens = new ArrayList<>();

    /** IP 白名单（支持 CIDR，空则不校验 IP） */
    private List<String> allowedIps = new ArrayList<>();

    /** Basic Auth 配置 */
    private BasicAuthConfig basicAuth = new BasicAuthConfig();

    @Data
    public static class TokenEntry {
        /** Token 归属名称（用于审计） */
        private String name;
        /** Token 值 */
        private String token;
    }

    @Data
    public static class BasicAuthConfig {
        private boolean enabled = false;
        private String username = "admin";
        private String password = "changeme";
    }
}
