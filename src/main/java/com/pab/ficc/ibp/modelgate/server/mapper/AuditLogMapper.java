package com.pab.ficc.ibp.modelgate.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Constants;
import com.baomidou.mybatisplus.extension.toolkit.SqlHelper;
import com.pab.ficc.ibp.modelgate.server.domain.entity.AuditLogEntity;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.logging.LogFactory;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Mapper
public interface AuditLogMapper extends BaseMapper<AuditLogEntity> {

    default boolean batchInsert(List<AuditLogEntity> list) {
        if (list == null || list.isEmpty()) return true;
        String statement = SqlHelper.getSqlStatement(AuditLogMapper.class,
                com.baomidou.mybatisplus.core.enums.SqlMethod.INSERT_ONE);
        return SqlHelper.executeBatch(AuditLogEntity.class, LogFactory.getLog(AuditLogMapper.class),
                list, 1000, (sqlSession, entity) -> sqlSession.insert(statement, entity));
    }

    @Delete("DELETE FROM mg_audit_log WHERE create_time < #{cutoff} LIMIT #{limit}")
    int deleteBefore(@Param("cutoff") LocalDateTime cutoff, @Param("limit") int limit);

    /**
     * 用量统计汇总。
     *
     * <p><b>appId 由单值改为列表（2026-08-24）</b>：管理面新增了「应用编码」筛选，
     * 一个应用编码可能对应多个 appId（{@code mg_app.app_code} 只做"系统内唯一"）。
     * 统计卡片必须与下方审计列表<b>同口径</b>——否则列表按编码筛出 3 个应用的数据，
     * 卡片却显示全量汇总，用户会拿着错误的数字去对账。
     *
     * <p>{@code appIds} 为 null 或空表示不按应用过滤。调用方（{@code UsageStatsController}）
     * 已经通过 {@code AppScopeResolver} 保证：编码查无应用时<b>根本不会调到这里</b>，
     * 而是直接返回全零——所以这里不需要区分"空列表=不过滤"和"空列表=查不到"。
     */
    @Select("<script>" +
            "SELECT COUNT(*) AS totalRequests, " +
            "COALESCE(SUM(input_tokens), 0) AS totalInputTokens, " +
            "COALESCE(SUM(output_tokens), 0) AS totalOutputTokens, " +
            "COALESCE(AVG(latency_ms), 0) AS avgLatencyMs, " +
            "COALESCE(SUM(CASE WHEN http_status &lt; 400 THEN 1 ELSE 0 END), 0) AS successCount " +
            "FROM mg_audit_log " +
            // 只统计模型流量：本表自 2026-08-27 起同时承载通用服务流量（traffic_type='service'），
            // 那些行的 model_id 为 NULL、也没有 Token。不加这个条件的话，
            // 「总请求数」会把服务调用一起算进模型用量，而平均耗时会被服务的短请求拉低——
            // 报表看起来正常，数字却是错的，对账时才会发现。
            // 历史数据由列默认值 'model' 自动归位，无需回填。
            "WHERE traffic_type = 'model' " +
            "AND create_time BETWEEN #{start} AND #{end} " +
            "<if test='appIds != null and appIds.size() > 0'>" +
            "AND app_id IN <foreach item='a' collection='appIds' open='(' separator=',' close=')'>#{a}</foreach> " +
            "</if>" +
            "AND (#{modelId} IS NULL OR model_id = #{modelId})" +
            "</script>")
    Map<String, Object> selectUsageStats(@Param("appIds") List<String> appIds,
                                         @Param("modelId") String modelId,
                                         @Param("start") LocalDateTime start,
                                         @Param("end") LocalDateTime end);
}
