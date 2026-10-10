package com.pab.ficc.ibp.modelgate.server.controller;

import com.pab.ficc.ibp.modelgate.server.adapter.ProviderAdapterRegistry;
import com.pab.ficc.ibp.modelgate.server.constant.UrlConstant;
import com.pab.ficc.ibp.modelgate.server.domain.Result;
import com.pab.ficc.ibp.modelgate.server.domain.vo.ProviderAdapterVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import java.util.List;

@RestController
@RequestMapping(UrlConstant.MG_MODELS)
@RequiredArgsConstructor
public class ProviderAdapterController {
    private final ProviderAdapterRegistry registry;
    @GetMapping(UrlConstant.PROVIDER_ADAPTERS)
    public Mono<Result<List<ProviderAdapterVO>>> providerAdapters() {
        return Mono.fromSupplier(() -> Result.success(registry.descriptors()));
    }
}
