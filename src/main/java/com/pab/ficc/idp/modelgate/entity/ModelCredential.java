package com.pab.ficc.idp.modelgate.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("mg_model_credential")
public class ModelCredential {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String modelId;
    private String authType;
    private String accessKey;
    private String secretKey;
    private String extraConfig;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
