package com.pab.ficc.ibp.modelgate.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
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
    /** 仅排除已知个人，保留无 appId 的拒绝记录和已删除系统的历史。 */
    String SYSTEM_AUDIT_SCOPE = "NOT EXISTS (SELECT 1 FROM mg_app p WHERE p.app_id = mg_audit_log.app_id "
            + "AND p.subject_type = 'PERSONAL')";

    default boolean batchInsert(List<AuditLogEntity> list) {
        if (list == null || list.isEmpty()) return true;
        String statement = SqlHelper.getSqlStatement(AuditLogMapper.class,
                com.baomidou.mybatisplus.core.enums.SqlMethod.INSERT_ONE);
        return SqlHelper.executeBatch(AuditLogEntity.class, LogFactory.getLog(AuditLogMapper.class),
                list, 1000, (sqlSession, entity) -> sqlSession.insert(statement, entity));
    }

    @Delete("DELETE FROM mg_audit_log WHERE create_time < #{cutoff} LIMIT #{limit}")
    int deleteBefore(@Param("cutoff") LocalDateTime cutoff, @Param("limit") int limit);

    /** 仅接受后端通过 UM 解析出的单个个人 ID；没有“空 ID = 全量”分支。 */
    @Select("""
            SELECT model_id, COUNT(*) AS total_requests,
                   COALESCE(SUM(input_tokens), 0) AS input_tokens,
                   COALESCE(SUM(output_tokens), 0) AS output_tokens,
                   COALESCE(SUM(CASE WHEN token_source = 'usage' THEN input_tokens + output_tokens ELSE 0 END), 0)
                       AS upstream_tokens,
                   COALESCE(SUM(CASE WHEN token_source = 'estimated' THEN input_tokens + output_tokens ELSE 0 END), 0)
                       AS estimated_tokens
            FROM mg_audit_log
            WHERE app_id = #{appId} AND traffic_type = 'model'
              AND create_time >= #{start} AND create_time < #{endExclusive}
            GROUP BY model_id
            ORDER BY model_id
            """)
    List<Map<String, Object>> selectPersonalUsageByModel(@Param("appId") String appId,
                                                         @Param("start") LocalDateTime start,
                                                         @Param("endExclusive") LocalDateTime endExclusive);

    @Select("""
            SELECT COUNT(*) AS totalRequests,
                   COALESCE(SUM(input_tokens), 0) AS totalInputTokens,
                   COALESCE(SUM(output_tokens), 0) AS totalOutputTokens,
                   COALESCE(AVG(latency_ms), 0) AS avgLatencyMs,
                   COALESCE(SUM(CASE WHEN http_status < 400 THEN 1 ELSE 0 END), 0) AS successCount
            FROM mg_audit_log ${ew.customSqlSegment}
            """)
    Map<String, Object> selectFilteredUsageStats(@Param(Constants.WRAPPER) Wrapper<AuditLogEntity> wrapper);
}
