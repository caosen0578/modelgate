package com.pab.ficc.ibp.modelgate.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.toolkit.SqlHelper;
import com.pab.ficc.ibp.modelgate.server.domain.entity.RequestPayloadEntity;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.logging.LogFactory;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface RequestPayloadMapper extends BaseMapper<RequestPayloadEntity> {

    /** 批量插入，与 {@code AuditLogMapper.batchInsert} 同一套写法（每 1000 条一批）。 */
    default boolean batchInsert(List<RequestPayloadEntity> list) {
        if (list == null || list.isEmpty()) return true;
        String statement = SqlHelper.getSqlStatement(RequestPayloadMapper.class,
                com.baomidou.mybatisplus.core.enums.SqlMethod.INSERT_ONE);
        return SqlHelper.executeBatch(RequestPayloadEntity.class, LogFactory.getLog(RequestPayloadMapper.class),
                list, 1000, (sqlSession, entity) -> sqlSession.insert(statement, entity));
    }

    /**
     * 按保留期清理。
     * 带 LIMIT 分批删除：报文行含 LONGTEXT，一次性删大批量会长时间持锁并撑大 binlog。
     */
    @Delete("DELETE FROM mg_request_payload WHERE create_time < #{cutoff} LIMIT #{limit}")
    int deleteBefore(@Param("cutoff") LocalDateTime cutoff, @Param("limit") int limit);
}
