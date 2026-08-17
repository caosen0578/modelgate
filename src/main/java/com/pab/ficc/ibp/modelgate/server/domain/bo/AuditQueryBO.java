package com.pab.ficc.ibp.modelgate.server.domain.bo;

import com.pab.ficc.ibp.domain.base.bo.BaseBo;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 审计日志查询入参：行内分页字段（pageIndex / pageSize）+ 原有四个过滤条件。
 *
 * <p>改分页前是硬编码 {@code LIMIT 200}：超过 200 条的历史记录**根本查不到**，
 * 而审计表恰恰是全库增长最快的表，这个上限等于让排查能力随时间失效。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class AuditQueryBO extends BaseBo {

    /** 系统 ID，为空不过滤。 */
    private String appId;

    /** 模型 ID，为空不过滤。 */
    private String modelId;

    /** HTTP 状态码，null 不过滤。 */
    private Integer status;

    /** 链路追踪 ID，为空不过滤。 */
    private String traceId;
}
