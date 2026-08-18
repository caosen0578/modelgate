package com.pab.ficc.ibp.modelgate.server.mapper;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Constants;
import com.baomidou.mybatisplus.extension.toolkit.SqlHelper;
import com.pab.ficc.ibp.modelgate.server.domain.entity.RequestPayloadEntity;
import com.pab.ficc.ibp.modelgate.server.domain.vo.PayloadListVO;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
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

    /**
     * 管理面列表分页查询。
     *
     * <p><b>不能用 {@code selectPage(page, wrapper)}</b>：BaseMapper 的默认实现是
     * {@code SELECT *}，会把 request_body / response_raw / response_text 三个 LONGTEXT
     * 一起拖出来——一页 20 条就可能是几十 MB，DB 网络、服务端堆、浏览器渲染一起遭殃。
     * 这里显式列出小字段，正文只取<b>长度</b>和<b>前 160 字预览</b>，都在 SQL 侧算完，
     * 大列的内容根本不出 MySQL。全文由 {@code selectById} 单条按需取。
     *
     * <p>{@code ${ew.customSqlSegment}} 展开为 Wrapper 生成的 WHERE + ORDER BY；
     * COUNT 语句由分页插件自动派生。
     */
    @Select("SELECT id, trace_id, app_id, model_id, is_stream, finish_reason, upstream_status, " +
            "       truncated, create_time, " +
            "       CHAR_LENGTH(request_body)  AS request_len, " +
            "       CHAR_LENGTH(response_raw)  AS response_raw_len, " +
            "       CHAR_LENGTH(response_text) AS response_text_len, " +
            "       LEFT(response_text, 160)   AS response_preview " +
            "  FROM mg_request_payload ${ew.customSqlSegment}")
    IPage<PayloadListVO> selectListPage(IPage<PayloadListVO> page,
                                        @Param(Constants.WRAPPER) Wrapper<RequestPayloadEntity> wrapper);
}
