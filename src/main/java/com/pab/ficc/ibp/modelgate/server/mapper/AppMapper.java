package com.pab.ficc.ibp.modelgate.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pab.ficc.ibp.modelgate.server.domain.entity.AppInfoEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AppMapper extends BaseMapper<AppInfoEntity> {
    // 保留已停用、已逻辑删除的个人身份对历史审计的排除；不能套用 is_del='0'。
    String PERSONAL_APP_IDS = "SELECT app_id FROM mg_app WHERE subject_type = 'PERSONAL'";

    @Select("SELECT COUNT(*) FROM mg_app WHERE app_id = #{appId}")
    long countHistoricalAppId(@Param("appId") String appId);
}
