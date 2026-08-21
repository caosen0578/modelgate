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
 * <p><b>正文关键字的使用约束</b>：正文检索是 {@code LIKE '%x%'}，在 LONGTEXT 上无法走索引，
 * 单独使用等于全表扫描并把几十 GB 正文拖进内存。故要求必须与足够长的 traceId 前缀，
 * 或两端齐全且跨度不超过 24 小时的时间范围同时使用（在 Controller 中校验）。
 * appId / modelId 只能定位、不能给候选行数设上界，不能单独作为正文检索的准入条件。
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

    /**
     * 兼容旧调用方的正文关键字，同时匹配 request_body 与 response_text。
     * 新页面按用户 ID 排查时改传 {@link #requestKeyword}，避免无意义地扫描响应正文。
     */
    private String keyword;

    /** 请求报文关键字，只匹配 request_body。⚠️ 必须搭配收窄条件，见类注释。 */
    private String requestKeyword;

    /** traceId 前缀至少要这么长才算"能收窄"——见 {@link #hasNarrowingFilter()}。 */
    private static final int TRACE_PREFIX_MIN = 8;

    /** 时间范围跨度不超过这么多小时才算"能收窄"——见 {@link #hasNarrowingFilter()}。 */
    private static final long TIME_SPAN_MAX_HOURS = 24;

    /**
     * 是否给出了能<b>给候选集设上界</b>的条件——keyword 检索的前置条件。
     *
     * <p><b>只认两个</b>：足够长的 traceId 前缀（近乎唯一），或有界的时间范围。
     *
     * <p><b>appId / modelId 刻意不算</b>（这一版收紧了）。它们能走 {@code idx_app_model_time}
     * 做<b>定位</b>，但不给候选集任何<b>上界</b>：内网网关通常只接个位数的系统，
     * 主力 appId 往往占全表七成到十成——{@code keyword=xxx&appId=主力系统} 通过校验后，
     * 实际执行的仍然是近乎全表的 LONGTEXT 扫描。在"能不能收窄"这件事上，
     * 单独的 appId 和被显式拒绝的 {@code upstreamStatus=200} 是同一档，不该区别对待。
     * 它们仍然是有用的<b>过滤条件</b>，只是不能单独充当 keyword 的准入券。
     *
     * <p>{@code isStream} / {@code upstreamStatus} / {@code finishReason} / {@code truncated}
     * 同样不算：无索引且区分度极低。
     *
     * <p>traceId 要求至少 {@value #TRACE_PREFIX_MIN} 位：前缀匹配下 1 位十六进制只能滤掉
     * 15/16 的行，用它当"收窄条件"等于没有。
     *
     * <p><b>时间范围必须两端都给且跨度 ≤ {@value #TIME_SPAN_MAX_HOURS} 小时</b>。
     * 早先只判 {@code != null}，于是 {@code startTime=1990-01-01} 这种一眼假的下界
     * 也算"已收窄"——实测 {@code keyword=hello&startTime=1990-01-01} 直接放行，
     * 等于防护形同虚设。只给单端同理：只有 startTime 就是"从某时刻到现在"，
     * 在保留期只有 7 天的表上几乎等于全表。
     *
     * <p>顺带解决排序问题：有了 ≤24h 的窗口，MySQL 可以用 {@code idx_create_time} 做范围扫描，
     * {@code ORDER BY create_time DESC} 由索引反向扫描直接满足；否则只给 appId 时索引内是
     * {@code (model_id, create_time)} 序，排序落到 filesort，而 filesort 要为<b>每一条候选行</b>
     * 算出 {@code CHAR_LENGTH}/{@code LEFT}，也就是把三个 LONGTEXT 全读出来。
     */
    public boolean hasNarrowingFilter() {
        return hasTraceNarrow() || hasBoundedTimeRange();
    }

    /** traceId 前缀是否长到足以收窄 */
    public boolean hasTraceNarrow() {
        return notBlank(traceId) && traceId.trim().length() >= TRACE_PREFIX_MIN;
    }

    /** 时间范围是否两端齐全且跨度足够小 */
    public boolean hasBoundedTimeRange() {
        if (startTime == null || endTime == null || endTime.isBefore(startTime)) {
            return false;
        }
        return java.time.Duration.between(startTime, endTime).toHours() <= TIME_SPAN_MAX_HOURS;
    }

    /** 供错误文案引用，避免提示里写死的数字与校验逻辑漂移 */
    public static int tracePrefixMin() {
        return TRACE_PREFIX_MIN;
    }

    public static long timeSpanMaxHours() {
        return TIME_SPAN_MAX_HOURS;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
