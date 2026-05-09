package com.pab.ficc.idp.modelgate.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("mg_audit_log")
public class AuditLog {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String traceId;
    private String appId;
    private String modelId;
    private String credType;
    private String clientIp;
    private String requestPath;
    private Integer httpStatus;
    private Integer latencyMs;
    private Integer inputTokens;
    private Integer outputTokens;
    private String errorMsg;
    private LocalDateTime createdAt;
}
