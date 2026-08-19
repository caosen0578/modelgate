package com.pab.ficc.ibp.modelgate.server.domain.vo;

import com.pab.ficc.ibp.modelgate.server.domain.entity.AccessRuleEntity;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AccessRuleVO {

    private Long   id;
    private String appId;
    private String ruleType;
    private String ruleValue;
    private String remark;

    /** 创建时间。与 updateTime 一样，此前 VO 里完全没有，接口从不返回。 */
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

    public static AccessRuleVO of(AccessRuleEntity e) {
        AccessRuleVO vo = new AccessRuleVO();
        vo.id        = e.getId();
        vo.appId     = e.getAppId();
        vo.ruleType  = e.getRuleType();
        vo.ruleValue = e.getRuleValue();
        vo.remark    = e.getRemark();
        vo.createTime = e.getCreateTime();
        vo.updateTime = e.getUpdateTime();
        return vo;
    }
}
