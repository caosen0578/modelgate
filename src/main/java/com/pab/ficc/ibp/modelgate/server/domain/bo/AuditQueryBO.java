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

    /** 接入系统 appId，为空不过滤。走 {@code idx_app_id}。 */
    private String appId;

    /**
     * 应用编码（{@code mg_app.app_code}），为空不过滤。<b>精确匹配</b>。
     *
     * <p>审计表里没有这一列，由 {@code AppScopeResolver} 先在 {@code mg_app} 解析成一组 appId
     * 再以 {@code app_id IN (...)} 查询；与 {@code appId} 同时给出时取交集。
     * 详见该类注释（含"为什么不把 app_code 冗余进审计表"的取舍）。
     */
    private String appCode;

    /** 模型 ID，为空不过滤。 */
    private String modelId;

    /** HTTP 状态码，null 不过滤。 */
    private Integer status;

    /** 链路追踪 ID，为空不过滤。 */
    private String traceId;

    /**
     * 流量类型：{@code model} / {@code service}，为空<b>不过滤</b>（两类都返回）。
     *
     * <p>不过滤是刻意的默认值：审计页的本职是"这段时间发生过什么"，
     * 默认藏起一半流量会让人误以为服务调用没进审计。
     * 用量统计那边则相反——{@code selectUsageStats} 硬编码了
     * {@code traffic_type='model'}，因为把服务调用算进模型用量是错的。
     * 两处的默认取向不同，是因为一个是"看记录"、一个是"算数字"。
     */
    private String trafficType;

    /** 服务编码，为空不过滤。<b>精确匹配</b>，与 modelId 互斥使用（一条记录只会有其中一个）。 */
    private String serviceId;

}
