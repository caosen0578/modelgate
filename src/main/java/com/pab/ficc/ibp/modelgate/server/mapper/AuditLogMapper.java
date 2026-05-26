package com.pab.ficc.ibp.modelgate.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Constants;
import com.baomidou.mybatisplus.extension.toolkit.SqlHelper;
import com.pab.ficc.ibp.modelgate.server.domain.entity.AuditLog;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.logging.LogFactory;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Mapper
public interface AuditLogMapper extends BaseMapper<AuditLog> {

    default boolean batchInsert(List<AuditLog> list) {
        if (list == null || list.isEmpty()) return true;
        String statement = SqlHelper.getSqlStatement(AuditLogMapper.class,
                com.baomidou.mybatisplus.core.enums.SqlMethod.INSERT_ONE);
        return SqlHelper.executeBatch(AuditLog.class, LogFactory.getLog(AuditLogMapper.class),
                list, 1000, (sqlSession, entity) -> sqlSession.insert(statement, entity));
    }

    @Delete("DELETE FROM mg_audit_log WHERE created_at < #{cutoff} LIMIT #{limit}")
    int deleteBefore(@Param("cutoff") LocalDateTime cutoff, @Param("limit") int limit);

    @Select("SELECT COUNT(*) AS totalRequests, " +
            "COALESCE(SUM(input_tokens), 0) AS totalInputTokens, " +
            "COALESCE(SUM(output_tokens), 0) AS totalOutputTokens, " +
            "COALESCE(AVG(latency_ms), 0) AS avgLatencyMs, " +
            "COALESCE(SUM(CASE WHEN http_status < 400 THEN 1 ELSE 0 END), 0) AS successCount " +
            "FROM mg_audit_log " +
            "WHERE created_at BETWEEN #{start} AND #{end} " +
            "AND (#{appId} IS NULL OR app_id = #{appId}) " +
            "AND (#{modelId} IS NULL OR model_id = #{modelId})")
    Map<String, Object> selectUsageStats(@Param("appId") String appId,
                                         @Param("modelId") String modelId,
                                         @Param("start") LocalDateTime start,
                                         @Param("end") LocalDateTime end);
}
