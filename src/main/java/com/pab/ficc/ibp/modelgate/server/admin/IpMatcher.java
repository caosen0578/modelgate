package com.pab.ficc.ibp.modelgate.server.admin;

import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

/**
 * IP / CIDR / 域名匹配工具
 *
 * <p>支持的规则类型：
 * <ul>
 *   <li>精确 IP — "192.168.1.5"</li>
 *   <li>CIDR 段 — "10.244.0.0/16"（K8s Pod 网段）</li>
 *   <li>精确域名 — "crm.internal.com"（匹配 Origin 请求头）</li>
 *   <li>通配域名 — "*.internal.com"（匹配 Origin 请求头的单级子域）</li>
 * </ul>
 *
 * <p>K8s 场景说明：
 * <ul>
 *   <li>K8s 内部服务（Pod IP 动态变化）→ 用 CIDR 规则覆盖 Pod 网段</li>
 *   <li>办公区用户（固定 IP 或子网）→ 用 ip 或 cidr 规则</li>
 *   <li>浏览器前端（有 Origin 头）→ 用 domain / domain_wildcard 规则</li>
 *   <li>规则列表为空 → 放行所有来源</li>
 * </ul>
 */
@Component
public class IpMatcher {

    /**
     * 判断 ip 是否在 allowedIps 列表中（任意一个匹配即通过）。
     * 列表为空时直接放行。
     */
    public boolean matches(String ip, List<String> allowedIps) {
        if (allowedIps == null || allowedIps.isEmpty()) return true;
        for (String allowed : allowedIps) {
            if (matchesCidr(ip, allowed)) return true;
        }
        return false;
    }

    /**
     * CIDR / 精确 IP 匹配。
     * 支持 IPv4 CIDR（如 10.244.0.0/16）和精确 IP 地址。
     */
    public boolean matchesCidr(String ip, String cidr) {
        try {
            if (!cidr.contains("/")) {
                return ip.equals(cidr);
            }
            String[] parts = cidr.split("/");
            int prefixLength = Integer.parseInt(parts[1]);
            InetAddress network = InetAddress.getByName(parts[0]);
            InetAddress target  = InetAddress.getByName(ip);

            byte[] networkBytes = network.getAddress();
            byte[] targetBytes  = target.getAddress();
            if (networkBytes.length != targetBytes.length) return false;

            for (int i = 0; i < prefixLength; i++) {
                int byteIndex  = i / 8;
                int bitIndex   = 7 - (i % 8);
                int networkBit = (networkBytes[byteIndex] >> bitIndex) & 1;
                int targetBit  = (targetBytes[byteIndex]  >> bitIndex) & 1;
                if (networkBit != targetBit) return false;
            }
            return true;
        } catch (UnknownHostException | NumberFormatException e) {
            return false;
        }
    }

    /**
     * 精确域名匹配（大小写不敏感）。
     * 例：ruleValue="crm.internal.com", clientDomain="crm.internal.com" → true
     */
    public boolean matchesDomain(String clientDomain, String ruleValue) {
        if (clientDomain == null || ruleValue == null) return false;
        return clientDomain.equalsIgnoreCase(ruleValue);
    }

    /**
     * 通配域名匹配（仅支持 *.xxx 单级通配，大小写不敏感）。
     * 例：ruleValue="*.internal.com", clientDomain="crm.internal.com" → true
     *     ruleValue="*.internal.com", clientDomain="a.b.internal.com" → false（跨级不匹配）
     */
    public boolean matchesDomainWildcard(String clientDomain, String ruleValue) {
        if (clientDomain == null || ruleValue == null) return false;
        if (!ruleValue.startsWith("*.")) {
            return clientDomain.equalsIgnoreCase(ruleValue);
        }
        // ruleValue = "*.internal.com"  →  suffix = ".internal.com"
        String suffix = ruleValue.substring(1).toLowerCase();
        String lower  = clientDomain.toLowerCase();
        // 必须以 suffix 结尾，且前缀部分不含点（单级匹配）
        if (!lower.endsWith(suffix)) return false;
        String prefix = lower.substring(0, lower.length() - suffix.length());
        return !prefix.isEmpty() && !prefix.contains(".");
    }
}
