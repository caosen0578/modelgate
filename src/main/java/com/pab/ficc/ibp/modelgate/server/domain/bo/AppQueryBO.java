package com.pab.ficc.ibp.modelgate.server.domain.bo;

import com.pab.ficc.ibp.domain.base.bo.BaseBo;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 接入系统列表查询入参：行内分页字段（pageIndex / pageSize）+ 过滤条件。
 *
 * <p>关键字放服务端做，理由同 {@link ModelQueryBO}。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class AppQueryBO extends BaseBo {

    /** 模糊匹配 appId / systemName / appName / sysCode，为空表示不过滤。 */
    private String keyword;

    /** 按启用状态过滤，null 表示不过滤。 */
    private Boolean enabled;
}
