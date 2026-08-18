package com.pab.ficc.ibp.modelgate.server.mapper;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Constants;
import com.baomidou.mybatisplus.extension.toolkit.SqlHelper;
import com.pab.ficc.ibp.modelgate.server.domain.entity.RequestPayloadEntity;
import com.pab.ficc.ibp.modelgate.server.domain.vo.PayloadDetailVO;
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
     * <p><b>Token 用标量子查询取，不用 JOIN</b>，两个理由缺一不可：
     * <ol>
     *   <li><b>正确性</b>：{@code mg_request_payload} 与 {@code mg_audit_log} 的 trace_id 上都只有
     *       普通索引、没有唯一约束。用 LEFT JOIN 时，审计侧一旦出现同 trace_id 的多行，
     *       报文行会被放大成多行，分页 total 与列表内容一起错乱。标量子查询带 LIMIT 1，
     *       结构上保证一条报文只出一行。</li>
     *   <li><b>兼容 Wrapper</b>：{@code ${ew.customSqlSegment}} 生成的是<b>不带表前缀</b>的列名
     *       （{@code app_id = ?}、{@code ORDER BY create_time}）。一旦 JOIN 进第二张表，
     *       这些列全部变成 ambiguous，SQL 直接报错——除非把整个查询改成手写参数，
     *       那样就丢掉了条件组合的灵活性。</li>
     * </ol>
     * 子查询走 {@code mg_audit_log.idx_trace_id}，每行三次索引点查，一页 20 行可忽略。
     *
     * <p>Token 之所以要跨表取：报文表<b>刻意不冗余存 Token</b>（唯一真相在 mg_audit_log，
     * 见 {@code RequestPayloadEntity} 类注释）。这里读取而不复制，不破坏该约定。
     *
     * <p>{@code ${ew.customSqlSegment}} 展开为 Wrapper 生成的 WHERE + ORDER BY；
     * COUNT 语句由分页插件自动派生。
     */
    @Select("SELECT id, trace_id, app_id, model_id, is_stream, finish_reason, upstream_status, " +
            "       truncated, create_time, " +
            "       CHAR_LENGTH(request_body)  AS request_len, " +
            "       CHAR_LENGTH(response_raw)  AS response_raw_len, " +
            "       CHAR_LENGTH(response_text) AS response_text_len, " +
            "       LEFT(request_body,  160)   AS request_preview, " +
            "       LEFT(response_text, 160)   AS response_preview, " +
            "       (SELECT a.input_tokens  FROM mg_audit_log a " +
            "         WHERE a.trace_id = mg_request_payload.trace_id LIMIT 1) AS input_tokens, " +
            "       (SELECT a.output_tokens FROM mg_audit_log a " +
            "         WHERE a.trace_id = mg_request_payload.trace_id LIMIT 1) AS output_tokens, " +
            "       (SELECT a.token_source  FROM mg_audit_log a " +
            "         WHERE a.trace_id = mg_request_payload.trace_id LIMIT 1) AS token_source " +
            "  FROM mg_request_payload ${ew.customSqlSegment}")
    IPage<PayloadListVO> selectListPage(IPage<PayloadListVO> page,
                                        @Param(Constants.WRAPPER) Wrapper<RequestPayloadEntity> wrapper);

    /**
     * 单条详情：报文全文 + 跨表取的 Token。
     *
     * <p>不用 {@code selectById}：那只能拿到 {@code mg_request_payload} 自己的列，
     * 详情页要显示的 Token 在 {@code mg_audit_log} 里（报文表按设计不冗余存）。
     * 同样用标量子查询，理由见 {@link #selectListPage}。
     */
    @Select("SELECT id, trace_id, app_id, model_id, is_stream, request_body, response_raw, " +
            "       response_text, finish_reason, upstream_status, truncated, create_time, " +
            "       (SELECT a.input_tokens  FROM mg_audit_log a " +
            "         WHERE a.trace_id = mg_request_payload.trace_id LIMIT 1) AS input_tokens, " +
            "       (SELECT a.output_tokens FROM mg_audit_log a " +
            "         WHERE a.trace_id = mg_request_payload.trace_id LIMIT 1) AS output_tokens, " +
            "       (SELECT a.token_source  FROM mg_audit_log a " +
            "         WHERE a.trace_id = mg_request_payload.trace_id LIMIT 1) AS token_source " +
            "  FROM mg_request_payload WHERE id = #{id}")
    PayloadDetailVO selectDetailById(@Param("id") Long id);
}
