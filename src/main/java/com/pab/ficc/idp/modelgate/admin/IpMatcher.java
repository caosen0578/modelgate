package com.pab.ficc.idp.modelgate.admin;

import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

/**
 * IP / CIDR 匹配工具
 * 支持精确 IP（"192.168.1.1"）和 CIDR 段（"10.0.0.0/8"）
 */
@Component
public class IpMatcher {

    /**
     * 判断 ip 是否在 allowedIps 列表中（任意一个匹配即通过）
     *
     * @param ip         待校验的客户端 IP
     * @param allowedIps 允许的 IP / CIDR 列表，为空时直接放行
     */
    public boolean matches(String ip, List<String> allowedIps) {
        if (allowedIps == null || allowedIps.isEmpty()) {
            return true;
        }
        for (String allowed : allowedIps) {
            if (matchesCidr(ip, allowed)) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesCidr(String ip, String cidr) {
        try {
            if (!cidr.contains("/")) {
                return ip.equals(cidr);
            }
            String[] parts = cidr.split("/");
            int prefixLength = Integer.parseInt(parts[1]);
            InetAddress network = InetAddress.getByName(parts[0]);
            InetAddress target = InetAddress.getByName(ip);

            byte[] networkBytes = network.getAddress();
            byte[] targetBytes = target.getAddress();
            if (networkBytes.length != targetBytes.length) {
                return false;
            }

            // 逐位比较前 prefixLength 位
            for (int i = 0; i < prefixLength; i++) {
                int byteIndex = i / 8;
                int bitIndex = 7 - (i % 8);
                int networkBit = (networkBytes[byteIndex] >> bitIndex) & 1;
                int targetBit = (targetBytes[byteIndex] >> bitIndex) & 1;
                if (networkBit != targetBit) {
                    return false;
                }
            }
            return true;
        } catch (UnknownHostException | NumberFormatException e) {
            return false;
        }
    }
}
