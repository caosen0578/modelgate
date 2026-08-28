package com.pab.ficc.ibp.modelgate.server.domain.vo;

import com.pab.ficc.ibp.modelgate.server.domain.entity.AppInfoEntity;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AppVO {

    private String        appId;
    private String        systemName;
    private String        appName;
    private String        sysCode;
    private String        appCode;
    private String        owner;
    private String        remark;
    private Boolean       enabled;
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

    /** 已授权模型数（授权记录数，不区分是否生效）；列表页展示用 */
    private Long          authCount;
    /** 凭证数（含已禁用/已过期）；列表页展示用 */
    private Long          credCount;

    /**
     * 已授权的<b>通用服务</b>数量（{@code mg_app_service_auth}）。
     *
     * <p>与 {@link #authCount}（模型授权数）并列而不是合并：两者是完全独立的两套授权，
     * 加在一起会让"这个系统授权了 5 个"变成一句没法据以操作的话——
     * 运营要知道的是"模型 3 个、服务 2 个"，才知道该去哪个子面板改。
     */
    private Long          svcAuthCount;

    public static AppVO of(AppInfoEntity e) {
        AppVO vo = new AppVO();
        vo.appId     = e.getAppId();
        vo.systemName = e.getSystemName();
        vo.appName   = e.getAppName();
        vo.sysCode   = e.getSysCode();
        vo.appCode   = e.getAppCode();
        vo.owner     = e.getOwner();
        vo.remark    = e.getRemark();
        vo.enabled   = e.getEnabled();
        vo.createTime = e.getCreateTime();
        vo.updateTime = e.getUpdateTime();
        return vo;
    }

    /**
     * 列表页用：额外带上授权模型数与凭证数。
     *
     * <p>缺省归零而非留空——前端此前读不到这两个字段时靠 {@code ||0} 兜底，
     * 结果是"有数据也恒显示 0 个"，问题被兜底逻辑掩盖了。
     */
    public static AppVO of(AppInfoEntity e, Long authCount, Long credCount) {
        return of(e, authCount, credCount, 0L);
    }

    /**
     * 列表页用：授权模型数 + 凭证数 + <b>授权服务数</b>。
     *
     * <p>保留三参重载而不是直接改签名：改签名会让所有调用点一起变动，
     * 而这个 VO 被列表、详情、新增回显多处使用，漏改一处的表现是编译失败——
     * 那还算好的；更糟的是有人顺手补个 null 进去，于是那一处的计数恒为 0 且没人发现。
     */
    public static AppVO of(AppInfoEntity e, Long authCount, Long credCount, Long svcAuthCount) {
        AppVO vo = of(e);
        vo.authCount    = authCount    != null ? authCount    : 0L;
        vo.credCount    = credCount    != null ? credCount    : 0L;
        vo.svcAuthCount = svcAuthCount != null ? svcAuthCount : 0L;
        return vo;
    }
}
