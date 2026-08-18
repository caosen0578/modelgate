package com.pab.ficc.ibp.modelgate.server.domain.bo;

import com.pab.ficc.ibp.domain.base.bo.BaseBo;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDateTime;

/**
 * 报文记录查询入参：行内分页字段（pageIndex / pageSize）+ 检索条件。
 *
 * <p><b>为什么条件比审计页多</b>：审计是"这次调用成不成功"，报文是"这次调用到底说了什么"，
 * 后者的排查入口更杂——按结束原因找被截断的回答、按流式与否找 SSE 拼接问题、
 * 按上游状态码找非 200 的原始错误体，都是审计表看不出来的。
 *
 * <p><b>keyword 的使用约束</b>：正文检索是 {@code LIKE '%x%'}，在 LONGTEXT 上无法走索引，
 * 单独使用等于全表扫描并把几十 GB 正文拖进内存。故要求必须与
 * appId / modelId / traceId / 时间范围 中的至少一项同时使用（在 Controller 中校验），
 * 让 MySQL 能先用 {@code idx_app_model_time} / {@code idx_create_time} 把候选集收窄。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class PayloadQueryBO extends BaseBo {

    /** 接入系统 appId，为空不过滤。走 idx_app_model_time 前缀。 */
    private String appId;

    /** 模型 ID，为空不过滤。 */
    private String modelId;

    /** 链路追踪 ID，为空不过滤。与审计页共用同一个 traceId，可互相跳转。 */
    private String traceId;

    /** 是否流式：true=仅 SSE，false=仅非流式，null=不过滤。 */
    private Boolean isStream;

    /** 上游 HTTP 状态码，null 不过滤。用于捞出上游报错时的原始响应体。 */
    private Integer upstreamStatus;

    /** 结束原因（stop / length / tool_calls / content_filter），为空不过滤。 */
    private String finishReason;

    /** 截断标记（none / request / response / both），为空不过滤。 */
    private String truncated;

    /** 记录时间下界（含），为空不限。 */
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private LocalDateTime startTime;

    /** 记录时间上界（含），为空不限。 */
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private LocalDateTime endTime;

    /** 正文关键字，同时匹配 request_body 与 response_text。⚠️ 必须搭配其它条件，见类注释。 */
    private String keyword;

    /** 是否给出了能收窄候选集的条件——keyword 检索的前置条件。 */
    public boolean hasNarrowingFilter() {
        return notBlank(appId) || notBlank(modelId) || notBlank(traceId)
                || startTime != null || endTime != null;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
