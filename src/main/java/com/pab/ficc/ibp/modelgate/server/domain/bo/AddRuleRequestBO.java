package com.pab.ficc.ibp.modelgate.server.domain.bo;

import com.pab.ficc.ibp.modelgate.server.domain.entity.AccessRuleEntity;
import lombok.Data;

import javax.validation.constraints.NotBlank;

@Data
public class AddRuleRequestBO {

    @NotBlank private String appId;
    @NotBlank private String ruleType;
    @NotBlank private String ruleValue;
    private String remark;

    public AccessRuleEntity toEntity() {
        var r = new AccessRuleEntity();
        r.setRuleType(ruleType);
        r.setRuleValue(ruleValue);
        r.setRemark(remark);
        return r;
    }
}
