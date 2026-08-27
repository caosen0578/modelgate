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
    /**
     * 流量类型：{@code model} = 模型流量，{@code service} = 通用服务流量。
     *
     * <p><b>字段默认值 "model" 是刻意的兜底，不得删除</b>：DB 列是
     * {@code NOT NULL DEFAULT 'model'}，而 MyBatis-Plus 可能把 null 值以显式 NULL
     * 写进 INSERT、覆盖掉建表默认值，那会直接插入失败。与 {@code isDel = "0"} 同一类保险，
     * 理由见该字段注释（那个坑真实发生过，见 spec/modelgate/11 §17）。
     *
     * <p>现有用量统计 SQL 按 {@code model_id} 分组，服务流量必须能被排除，
     * 所以查询侧要补 {@code WHERE traffic_type = 'model'}；默认值保证历史数据自动归位。
     */
    private String trafficType = "model";
    private String appId;
    private String modelId;
    /** 服务编码（{@code traffic_type=service} 时写入；模型流量为 NULL） */
    private String serviceId;
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
