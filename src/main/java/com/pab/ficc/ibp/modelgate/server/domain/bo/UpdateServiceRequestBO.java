package com.pab.ficc.ibp.modelgate.server.domain.bo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.Data;

/**
 * 修改服务入参。
 *
 * <p><b>{@code serviceId} 只作为定位键，不可修改</b>：它是对外 URL 的一部分，
 * 改名等于换 URL、接入方必须同步改代码，那是"新建 + 下线"而不是"改名"。
 * 管理页面上该输入框编辑时置灰。
 *
 * <p>凭证字段留空的语义是<b>「保持原值」</b>而不是「清空」：
 * 列表与详情接口都不回显 SK 明文，若把留空当清空，运营改一次描述就会把凭证抹掉。
 * 要清空凭证请显式把 authType 改成 none。
 */
@Data
public class UpdateServiceRequestBO {

    /** 定位键，不可修改 */
    @NotBlank(message = "服务编码不能为空")
    private String serviceId;

    private String serviceName;
    private String ownerSystem;
    private String upstreamUrl;
    private Boolean stripPrefix;

    @PositiveOrZero(message = "超时时间不能为负")
    private Integer timeoutMs;

    private Boolean sse;
    private Boolean payloadLogEnabled;
    private String description;
    private Boolean enabled;

    // ===== 上游凭证；留空 = 保持原值 =====
    private String authType;
    private String accessKey;
    private String secretKey;
    private String extraHeaders;
}
