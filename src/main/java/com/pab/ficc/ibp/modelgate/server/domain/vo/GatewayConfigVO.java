package com.pab.ficc.ibp.modelgate.server.domain.vo;

import com.pab.ficc.ibp.modelgate.server.domain.entity.GatewayConfigEntity;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class GatewayConfigVO {

    private String        configKey;
    private String        configValue;
    private String        description;
    private String        updatedBy;
    private LocalDateTime updateTime;
    private LocalDateTime createTime;

    public static GatewayConfigVO of(GatewayConfigEntity e) {
        GatewayConfigVO vo = new GatewayConfigVO();
        vo.configKey   = e.getConfigKey();
        vo.configValue = e.getConfigValue();
        vo.description = e.getDescription();
        vo.updatedBy   = e.getUpdatedBy();
        vo.updateTime   = e.getUpdateTime();
        vo.createTime   = e.getCreateTime();
        return vo;
    }
}
