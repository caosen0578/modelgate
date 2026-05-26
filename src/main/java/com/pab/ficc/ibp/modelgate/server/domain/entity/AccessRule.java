package com.pab.ficc.ibp.modelgate.server.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("mg_access_rule")
public class




































































































AccessRule {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String appId;
    private String ruleType;
    private String ruleValue;
    private String remark;
    private LocalDateTime createdAt;
}
