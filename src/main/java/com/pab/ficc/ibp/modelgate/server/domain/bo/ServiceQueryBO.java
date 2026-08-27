package com.pab.ficc.ibp.modelgate.server.domain.bo;

import com.pab.ficc.ibp.domain.base.bo.BaseBo;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 服务列表查询入参：行内分页字段（pageIndex / pageSize）+ 过滤条件。
 *
 * <p>关键字在服务端过滤而非前端 filter——理由同 {@code ModelQueryBO}：
 * 服务端分页后前端手里只有当前页，前端过滤等于「只能搜到当前页」，搜索会静默失效。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class ServiceQueryBO extends BaseBo {

    /** 模糊匹配 serviceId 或 serviceName，为空表示不过滤。 */
    private String keyword;

    /** 按启用状态过滤，null 表示不过滤。 */
    private Boolean enabled;

    /** 按是否 SSE 服务过滤，null 表示不过滤。 */
    private Boolean sse;
}
