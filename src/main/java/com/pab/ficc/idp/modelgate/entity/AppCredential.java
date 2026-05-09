package com.pab.ficc.idp.modelgate.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("mg_app_credential")
public class AppCredential {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String appId;
    private String credName;
    private String credType;
    private String accessKey;
    private String secretHash;
    private String secretEncrypted;
    private Boolean enabled;
    private LocalDateTime expiresAt;
    private String remark;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
