package com.pab.ficc.idp.modelgate.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("mg_app_model_auth")
public class AppModelAuth {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String appId;
    private String modelId;
    private Boolean enabled;
    private Integer qpsLimit;
    private Integer dailyLimit;
    private Long monthlyTokens;
    private LocalDateTime expiresAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
