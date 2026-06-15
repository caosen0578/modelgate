package com.pab.ficc.ibp.modelgate.server.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.Components;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("ModelGate 管理接口")
                        .description("AI 模型流量网关 · 内部管理后台 API")
                        .version("v1.0"))
                .components(new Components()
                        .addSecuritySchemes("AdminToken",
                                new SecurityScheme()
                                        .type(SecurityScheme.Type.APIKEY)
                                        .in(SecurityScheme.In.HEADER)
                                        .name("X-Admin-Token")
                                        .description("管理员 Token，由运维下发")))
                .addSecurityItem(new SecurityRequirement().addList("AdminToken"));
    }
}
