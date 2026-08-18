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

    /**
     * 链路追踪 ID，为空不过滤。与审计页共用同一个 traceId，可互相跳转。
     *
     * <p><b>按前缀匹配而非全等</b>：列表页展示的 traceId 是截断过的（前 12 字符 + 省略号），
     * 用户复制看到的值来筛选是最自然的操作，若用全等就<b>永远查不到</b>——
     * 界面显示什么、筛选就得能接受什么。前缀匹配在 {@code idx_trace_id} 上是范围扫描，
     * 索引照样能用。
     */
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

    /** traceId 前缀至少要这么长才算"能收窄"——见 {@link #hasNarrowingFilter()}。 */
    private static final int TRACE_PREFIX_MIN = 8;

    /**
     * 是否给出了能收窄候选集的条件——keyword 检索的前置条件。
     *
     * <p><b>只认这几个</b>：appId / modelId 走 {@code idx_app_model_time}，
     * 时间范围走 {@code idx_create_time}，traceId 前缀走 {@code idx_trace_id}。
     * {@code isStream} / {@code upstreamStatus} / {@code finishReason} / {@code truncated}
     * <b>刻意不算</b>：它们没有索引且区分度极低（{@code upstream_status=200} 能占九成以上），
     * 加上它们照样是全表扫描 LONGTEXT，只会让人误以为已经收窄了。
     *
     * <p>traceId 要求至少 {@value #TRACE_PREFIX_MIN} 位：前缀匹配下 1 位十六进制只能滤掉
     * 15/16 的行，用它当"收窄条件"等于没有。
     */
    public boolean hasNarrowingFilter() {
        boolean traceNarrow = notBlank(traceId) && traceId.trim().length() >= TRACE_PREFIX_MIN;
        return notBlank(appId) || notBlank(modelId) || traceNarrow
                || startTime != null || endTime != null;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
