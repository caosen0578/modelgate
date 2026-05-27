package com.pab.ficc.ibp.modelgate.server.domain.vo;

import com.pab.ficc.ibp.modelgate.server.domain.entity.GatewayConfig;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class GatewayConfigVO {

    private String        configKey;
    private String        configValue;
    private String        description;
    private String        updatedBy;
    private LocalDateTime updatedAt;
    private LocalDateTime createdAt;

    public static GatewayConfigVO of(GatewayConfig e) {
        GatewayConfigVO vo = new GatewayConfigVO();
        vo.configKey   = e.getConfigKey();
        vo.configValue = e.getConfigValue();
        vo.description = e.getDescription();
        vo.updatedBy   = e.getUpdatedBy();
        vo.updatedAt   = e.getUpdatedAt();
        vo.createdAt   = e.getCreatedAt();
        return vo;
    }
}
