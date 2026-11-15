package com.pab.ficc.ibp.modelgate.server.admin;

import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PersonalSourcePolicyTest {
    private final PersonalSourcePolicy policy = new PersonalSourcePolicy(new IpMatcher(), "10.0.0.0/24, 10.0.1.15");

    @Test
    void takesNearestNonProxyAndIgnoresForgedPrefix() {
        assertEquals("203.0.113.30", policy.clientIp(new InetSocketAddress("10.0.1.15", 443),
                List.of("198.51.100.10, 203.0.113.30", "10.0.0.5")));
    }

    @Test
    void ignoresForwardingHeadersFromUntrustedPeerAndRequiresProxyChain() {
        assertEquals("203.0.113.30", policy.clientIp(new InetSocketAddress("203.0.113.30", 443),
                List.of("198.51.100.10")));
        assertNull(policy.clientIp(new InetSocketAddress("10.0.1.15", 443), List.of()));
    }

    @Test
    void validatesIpAndCidrWithoutResolvingHostNames() {
        assertEquals("2001:db8::1", PersonalSourcePolicy.normalizeRule("ip", "2001:0db8::1"));
        assertEquals("203.0.113.0/24", PersonalSourcePolicy.normalizeRule("cidr", "203.0.113.0/24"));
        assertEquals("203.0.113.30", PersonalSourcePolicy.normalizeRule("ip", "::ffff:203.0.113.30"));
        assertThrows(IllegalArgumentException.class, () -> PersonalSourcePolicy.normalizeRule("ip", "localhost"));
        assertThrows(IllegalArgumentException.class, () -> PersonalSourcePolicy.normalizeRule("cidr", "0.0.0.0/0"));
        assertThrows(IllegalArgumentException.class, () -> PersonalSourcePolicy.normalizeRule("cidr", "2001:db8::/129"));
    }
}
