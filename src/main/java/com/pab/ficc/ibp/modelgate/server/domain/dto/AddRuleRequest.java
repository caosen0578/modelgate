package com.pab.ficc.ibp.modelgate.server.domain.dto;

import com.pab.ficc.ibp.modelgate.server.domain.entity.AccessRule;
import lombok.Data;

import javax.validation.constraints.NotBlank;

@Data
public class AddRuleRequest {

    @NotBlank private String appId;
    @NotBlank private String ruleType;
    @NotBlank private String ruleValue;
    private String remark;

    public AccessRule toEntity() {
        var r = new AccessRule();
        r.setRuleType(ruleType);
        r.setRuleValue(ruleValue);
        r.setRemark(remark);
        return r;
    }
}
