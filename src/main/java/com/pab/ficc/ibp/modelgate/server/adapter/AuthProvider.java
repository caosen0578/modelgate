package com.pab.ficc.ibp.modelgate.server.adapter;

import reactor.core.publisher.Mono;

/** SPI：只对最终 method/URI/headers/body 签名；OAuth/STS 实现可异步获取凭证。 */
@FunctionalInterface
public interface AuthProvider {
    Mono<PreparedUpstreamRequest> authenticate(PreparedUpstreamRequest request, ProviderCredential credential);
}
