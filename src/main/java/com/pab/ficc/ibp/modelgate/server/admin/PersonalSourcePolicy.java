package com.pab.ficc.ibp.modelgate.server.admin;

import com.pab.ficc.ibp.modelgate.server.domain.entity.AccessRuleEntity;
import io.netty.util.NetUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.IDN;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** 个人 Key 只信任连接对端，以及已登记代理追加的来源地址。 */
@Component
public class PersonalSourcePolicy {
    private static final byte[] IPV4_MAPPED_PREFIX = {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, -1, -1};

    private final List<String> trustedProxies;
    private final IpMatcher ipMatcher;

    public PersonalSourcePolicy(IpMatcher ipMatcher,
                                @Value("${modelgate.personal-access.trusted-proxies:}") String trustedProxies) {
        this.ipMatcher = ipMatcher;
        this.trustedProxies = Arrays.stream(trustedProxies.split(","))
                .map(String::strip).filter(value -> !value.isEmpty())
                .map(value -> normalizeRule(value.contains("/") ? "cidr" : "ip", value))
                .toList();
    }

    public String clientIp(SocketAddress peerAddress, List<String> forwarded) {
        if (!(peerAddress instanceof InetSocketAddress remote) || remote.getAddress() == null) return null;
        String peer = formatIp(remote.getAddress().getAddress());
        if (!trusted(peer)) return peer;

        // 可信代理须清理外部 XFF 或在右侧追加实际连接来源。跳过已登记的代理，只取最靠右的非代理地址。
        if (forwarded.isEmpty()) return null;
        String[] hops = String.join(",", forwarded).split(",", -1);
        for (int index = hops.length - 1; index >= 0; index--) {
            String hop = normalizedIp(hops[index].strip());
            if (hop == null) return null;
            if (!trusted(hop)) return hop;
        }
        return null;
    }

    public boolean allows(String clientIp, String clientDomain, List<AccessRuleEntity> rules) {
        boolean ipMatches = false;
        boolean hasDomain = false;
        boolean domainMatches = false;
        for (AccessRuleEntity rule : rules) {
            switch (rule.getRuleType()) {
                case "ip", "cidr" -> ipMatches |= clientIp != null && rule.getRuleValue() != null
                        && ipMatcher.matchesCidr(clientIp, rule.getRuleValue());
                case "domain" -> {
                    hasDomain = true;
                    domainMatches |= ipMatcher.matchesDomain(clientDomain, rule.getRuleValue());
                }
                case "domain_wildcard" -> {
                    hasDomain = true;
                    domainMatches |= ipMatcher.matchesDomainWildcard(clientDomain, rule.getRuleValue());
                }
            }
        }
        // Origin / Referer 可由调用方自行设置，只能进一步收紧已经通过 IP 校验的请求。
        return ipMatches && (!hasDomain || domainMatches);
    }

    public static String normalizeRule(String type, String value) {
        if (value == null) value = "";
        if ("domain".equals(type) || "domain_wildcard".equals(type)) return normalizeDomain(type, value);
        String[] parts = value.strip().split("/", -1);
        String ip = normalizedIp(parts[0]);
        if (ip != null && "ip".equals(type) && parts.length == 1) return ip;
        if (ip != null && "cidr".equals(type) && parts.length == 2 && parts[1].matches("[0-9]{1,3}")) {
            int prefix = Integer.parseInt(parts[1]);
            if (prefix > 0 && prefix <= (ip.contains(":") ? 128 : 32)) return ip + "/" + prefix;
        }
        throw new IllegalArgumentException("请输入有效的来源 IP 或 CIDR（前缀长度至少为 1）");
    }

    private static String normalizeDomain(String type, String value) {
        String text = value.strip();
        boolean wildcard = "domain_wildcard".equals(type);
        if (wildcard == text.startsWith("*.")) {
            String host = wildcard ? text.substring(2) : text;
            try {
                String domain = IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
                if (!domain.isEmpty() && domain.length() <= 253 && !domain.endsWith(".")
                        && normalizedIp(domain) == null && (!wildcard || domain.contains("."))) {
                    return wildcard ? "*." + domain : domain;
                }
            } catch (IllegalArgumentException ignored) {
                // 非法域名统一返回可直接展示给录入人的提示。
            }
        }
        throw new IllegalArgumentException("请输入域名或 *.example.com，填写域名时去掉协议、端口和路径");
    }

    private boolean trusted(String ip) {
        return trustedProxies.stream().anyMatch(rule -> ipMatcher.matchesCidr(ip, rule));
    }

    private static String normalizedIp(String value) {
        if (value.contains("%") || value.startsWith("[")) return null;
        byte[] bytes = NetUtil.createByteArrayFromIpAddressString(value);
        return bytes == null ? null : formatIp(bytes);
    }

    private static String formatIp(byte[] bytes) {
        if (bytes.length == 16 && Arrays.equals(bytes, 0, 12, IPV4_MAPPED_PREFIX, 0, 12)) {
            return NetUtil.bytesToIpAddress(bytes, 12, 4);
        }
        return NetUtil.bytesToIpAddress(bytes);
    }
}
