package com.pab.ficc.ibp.modelgate.server.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("mg_audit_log")
public class AuditLogEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String traceId;
    private String appId;
    private String modelId;
    private String credType;
    private String clientIp;
    private String callerAppId;
    private String requestPath;
    private Integer httpStatus;
    private Integer latencyMs;
    private Integer inputTokens;
    private Integer outputTokens;

    /**
     * Token 来源口径：usage=上游精确返回 / estimated=本地估算 / none=无。
     * 统计报表必须能区分精确值与估算值，否则算出来的账没人敢认。
     */
    private String tokenSource;
    private String errorMsg;
    /** 事件时间（审计追加表，命名对齐基础字段 create_time；不含 is_del/creater 等其余基础字段） */
    private LocalDateTime createTime;
}
