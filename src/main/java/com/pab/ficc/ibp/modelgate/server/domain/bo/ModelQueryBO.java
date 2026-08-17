package com.pab.ficc.ibp.modelgate.server.domain.bo;

import com.pab.ficc.ibp.domain.base.bo.BaseBo;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 模型列表查询入参：行内分页字段（pageIndex / pageSize）+ 过滤条件。
 *
 * <p>关键字放服务端做而不是前端 filter：列表改服务端分页后，前端手里只有当前页，
 * 在前端过滤等于「只能搜到当前页」，搜索功能会静默失效。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class ModelQueryBO extends BaseBo {

    /** 模糊匹配 modelId 或 modelName，为空表示不过滤。 */
    private String keyword;

    /** 按提供商过滤，为空不过滤。 */
    private String provider;

    /** 按启用状态过滤，null 表示不过滤。 */
    private Boolean enabled;
}
