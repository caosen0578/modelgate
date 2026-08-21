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
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.logging.LogFactory;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface RequestPayloadMapper extends BaseMapper<RequestPayloadEntity> {

    /**
     * 管理面查询的 JDBC statement 超时（秒）。必须是编译期常量，注解里才能引用。
     *
     * <p>取 30 秒：正常查询在百毫秒级，跑到 30 秒说明条件写宽了或表涨太大，
     * 与其让它继续吃 IO，不如失败并让人重新收窄。这个值决定了单条慢查询占用
     * Druid 连接与线程的<b>上界</b>——没有它，上界是无穷。
     */
    int QUERY_TIMEOUT_SECONDS = 30;

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
     * {@code SELECT *}，会把 MEDIUMTEXT 请求头快照及 request_body / response_raw / response_text 三个 LONGTEXT
     * 一起拖出来——一页 20 条就可能是几十 MB，DB 网络、服务端堆、浏览器渲染一起遭殃。
     * 这里显式列出小字段，正文只取<b>长度</b>和<b>前 160 字预览</b>，都在 SQL 侧算完。
     *
     * <p>⚠️ <b>"大列不出 MySQL"只对网络与堆成立，对磁盘 I/O 与 buffer pool 不成立。</b>
     * {@code LEFT(...)} / {@code CHAR_LENGTH(...)} 是 <b>SQL 层</b>表达式，InnoDB 没有对应的
     * 下推能力：这两列一旦进了 read_set，存储引擎就会把行外存储（DYNAMIC 行格式下超过 40 字节的
     * LONGTEXT 存在 overflow 页链上）的<b>完整列</b>物化到行缓冲区，SQL 层才做截断。
     * 也就是说每经过一行，磁盘与 buffer pool 上的完整 LONGTEXT 读取一次也没省。
     *
     * <p>这条区分很重要，别按"反正大列不出 MySQL"去放宽限制：代价与<b>扫过的行数</b>成正比，
     * 而不是与返回的行数成正比。所以 {@code pageSize}（返回行数）、OFFSET（跳过的行数）、
     * 以及 WHERE 的候选集大小三者都必须封顶——见 {@code PayloadController.validate}。
     * 全文由 {@link #selectDetailById} 单条按需取。
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
     * <p>⚠️ <b>trace_id 不是唯一键，且调用方可以复用它。</b>
     * {@code TraceWebFilter.resolveTraceId} 明确<b>优先沿用</b>调用方传入的 {@code X-Trace-Id}
     * （设计意图就是跨系统串联），所以一次上游业务事务扇出成多次模型调用时，这几次调用天然带
     * 同一个 trace_id，两张表各写 N 行。此时子查询只能取到其中一行，N 行报文会显示<b>同一份</b>
     * Token——数字是错的且不会报错。
     * <ul>
     *   <li>三个取值子查询统一加 {@code ORDER BY a.id}：不加时"取哪一行"在 SQL 语义上未定义，
     *       三次独立求值理论上可以落到不同行，导致 input/output/source 张冠李戴。
     *       加了之后至少<b>确定且可复现</b>地取同一行（trace_id 下 id 最小的那条）。</li>
     *   <li>额外查一个 {@code audit_match_count}：命中多行时前端会把 Token 标成存疑，
     *       而不是安静地展示一个可能属于另一次调用的数字。宁可显示"不确定"，
     *       也不能显示一个看起来精确的错数。</li>
     * </ul>
     * 根治要么给报文行冗余 audit 行 id（需要改采集侧与 DDL），要么让调用方别复用 traceId；
     * 两者都超出本次范围，先做到"确定 + 可见"。
     *
     * <p>{@code ${ew.customSqlSegment}} 展开为 Wrapper 生成的 WHERE + ORDER BY；
     * COUNT 语句由分页插件自动派生（实测三个子查询与 LEFT/CHAR_LENGTH 都会被剥掉，
     * 但 <b>WHERE 原样保留</b>——带 keyword 时 LIKE 会在 COUNT 与数据页各跑一遍）。
     *
     * <p>{@code @Options(timeout)} 是 JDBC statement 级超时，会真正把语句在服务端取消。
     * 没有它的话，全链路（JDBC URL 无 socketTimeout、MybatisConfiguration 无
     * defaultStatementTimeout）没有任何东西能掐掉一条跑飞的查询——用户不耐烦连点几次，
     * 代价就是几倍而不是一倍，且最坏情况无上界。
     */
    @Options(timeout = QUERY_TIMEOUT_SECONDS)
    @Select("SELECT id, trace_id, app_id, model_id, is_stream, finish_reason, upstream_status, " +
            "       truncated, create_time, " +
            "       CHAR_LENGTH(request_headers) AS request_headers_len, " +
            "       CHAR_LENGTH(request_body)  AS request_len, " +
            "       CHAR_LENGTH(response_raw)  AS response_raw_len, " +
            "       CHAR_LENGTH(response_text) AS response_text_len, " +
            "       LEFT(request_headers, 160) AS request_headers_preview, " +
            "       LEFT(request_body,  160)   AS request_preview, " +
            "       LEFT(response_text, 160)   AS response_preview, " +
            "       (SELECT a.input_tokens  FROM mg_audit_log a " +
            "         WHERE a.trace_id = mg_request_payload.trace_id " +
            "         ORDER BY a.id LIMIT 1) AS input_tokens, " +
            "       (SELECT a.output_tokens FROM mg_audit_log a " +
            "         WHERE a.trace_id = mg_request_payload.trace_id " +
            "         ORDER BY a.id LIMIT 1) AS output_tokens, " +
            "       (SELECT a.token_source  FROM mg_audit_log a " +
            "         WHERE a.trace_id = mg_request_payload.trace_id " +
            "         ORDER BY a.id LIMIT 1) AS token_source, " +
            "       (SELECT COUNT(*)        FROM mg_audit_log a " +
            "         WHERE a.trace_id = mg_request_payload.trace_id) AS audit_match_count " +
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
    @Options(timeout = QUERY_TIMEOUT_SECONDS)
    @Select("SELECT id, trace_id, app_id, model_id, is_stream, request_headers, request_body, response_raw, " +
            "       response_text, finish_reason, upstream_status, truncated, create_time, " +
            "       (SELECT a.input_tokens  FROM mg_audit_log a " +
            "         WHERE a.trace_id = mg_request_payload.trace_id " +
            "         ORDER BY a.id LIMIT 1) AS input_tokens, " +
            "       (SELECT a.output_tokens FROM mg_audit_log a " +
            "         WHERE a.trace_id = mg_request_payload.trace_id " +
            "         ORDER BY a.id LIMIT 1) AS output_tokens, " +
            "       (SELECT a.token_source  FROM mg_audit_log a " +
            "         WHERE a.trace_id = mg_request_payload.trace_id " +
            "         ORDER BY a.id LIMIT 1) AS token_source, " +
            "       (SELECT COUNT(*)        FROM mg_audit_log a " +
            "         WHERE a.trace_id = mg_request_payload.trace_id) AS audit_match_count " +
            "  FROM mg_request_payload WHERE id = #{id}")
    PayloadDetailVO selectDetailById(@Param("id") Long id);
}
