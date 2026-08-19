package com.pab.ficc.ibp.modelgate.server.domain.vo;

import com.pab.ficc.ibp.modelgate.server.domain.entity.AppModelAuthEntity;
import com.pab.ficc.ibp.modelgate.server.domain.entity.ModelInstanceEntity;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AppModelAuthVO {

    private String        appId;
    private String        modelId;
    /** 模型展示名称；由列表接口联查回填。前端一直在读它，此前 VO 缺字段导致模型名列实际显示的是 ID。 */
    private String        modelName;
    /**
     * 模型类型 chat|embedding|image|audio，同由列表接口联查回填。
     * 前端「接入示例」据此决定给 chat/completions 还是 embeddings 的请求报文——
     * 给错路径的示例会让接入方直接踩坑，故必须随授权列表一起下发。
     */
    private String        modelType;
    private Boolean       enabled;
    private Integer       qpsLimit;
    private Integer       dailyLimit;
    private Long          monthlyTokens;
    /** 请求/响应报文记录开关；前端授权列表据此渲染开关状态 */
    private Boolean       payloadLogEnabled;
    private LocalDateTime expiresAt;
    private LocalDateTime createTime;
    /**
     * 更新时间。
     *
     * <p>DB 与 Entity 一直都有 {@code update_time}，唯独 VO 漏了它，接口因此从不返回——
     * 页面上的「更新时间」永远是空的，看起来像<b>数据库里没存值</b>，实际值一直都在。
     * 与 {@code AuditLogVO} 早先漏掉 {@code requestPath} 是同一类问题：
     * <b>字段缺失发生在 VO 层，症状却表现为「数据没保存」，极易误判。</b>
     */
    private LocalDateTime updateTime;

    public static AppModelAuthVO of(AppModelAuthEntity e) {
        AppModelAuthVO vo = new AppModelAuthVO();
        vo.appId         = e.getAppId();
        vo.modelId       = e.getModelId();
        vo.enabled       = e.getEnabled();
        vo.qpsLimit      = e.getQpsLimit();
        vo.dailyLimit    = e.getDailyLimit();
        vo.monthlyTokens = e.getMonthlyTokens();
        vo.payloadLogEnabled = Boolean.TRUE.equals(e.getPayloadLogEnabled());
        vo.expiresAt     = e.getExpiresAt();
        vo.createTime     = e.getCreateTime();
        vo.updateTime = e.getUpdateTime();
        return vo;
    }

    /** 带模型信息的转换；model 为 null（模型已删除但授权行残留）时前端回落显示 modelId。 */
    public static AppModelAuthVO of(AppModelAuthEntity e, ModelInstanceEntity model) {
        AppModelAuthVO vo = of(e);
        if (model != null) {
            vo.modelName = model.getModelName();
            vo.modelType = model.getModelType();
        }
        return vo;
    }
}
