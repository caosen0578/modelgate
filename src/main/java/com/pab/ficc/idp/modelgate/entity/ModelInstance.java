package com.pab.ficc.idp.modelgate.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("mg_model")
public class ModelInstance {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String modelId;
    private String modelName;
    private String provider;
    private String modelType;
    private String apiSpec;
    private String upstreamUrl;
    private Integer timeoutMs;
    private String defaultParams;
    private String forceParams;
    private String paramMapping;
    private String description;
    private Boolean enabled;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
