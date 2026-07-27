package com.pab.ficc.ibp.modelgate.server.domain.vo;

import com.pab.ficc.ibp.modelgate.server.domain.entity.AccessRuleEntity;
import lombok.Data;

@Data
public class AccessRuleVO {

    private Long   id;
    private String appId;
    private String ruleType;
    private String ruleValue;
    private String remark;

    public static AccessRuleVO of(AccessRuleEntity e) {
        AccessRuleVO vo = new AccessRuleVO();
        vo.id        = e.getId();
        vo.appId     = e.getAppId();
        vo.ruleType  = e.getRuleType();
        vo.ruleValue = e.getRuleValue();
        vo.remark    = e.getRemark();
        return vo;
    }
}
