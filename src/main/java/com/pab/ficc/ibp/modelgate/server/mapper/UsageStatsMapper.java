package com.pab.ficc.ibp.modelgate.server.mapper;

import com.pab.ficc.ibp.modelgate.server.domain.bo.UsageQueryBO;
import com.pab.ficc.ibp.modelgate.server.domain.vo.UsageReportViews.Amount;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Set;

@Mapper
public interface UsageStatsMapper {
    String AMOUNTS = """
            COUNT(*) AS requests, SUM(a.input_tokens) AS inputTokens, SUM(a.output_tokens) AS outputTokens,
            SUM(CASE WHEN a.token_source = 'usage' THEN a.input_tokens + a.output_tokens ELSE 0 END) AS upstreamTokens,
            SUM(CASE WHEN a.token_source = 'estimated' THEN a.input_tokens + a.output_tokens ELSE 0 END) AS estimatedTokens,
            SUM(CASE WHEN a.token_source IN ('usage', 'estimated') THEN 0 ELSE 1 END) AS missingTokenRequests
            """;
    String FILTERS = """
            WHERE a.traffic_type = 'model' AND a.model_id IS NOT NULL AND a.model_id != ''
              AND a.create_time &gt;= #{q.startTime} AND a.create_time &lt;= #{q.endTime}
            <if test="q.modelId != null and q.modelId != ''">AND a.model_id = #{q.modelId}</if>
            <if test="q.modelIds != null and !q.modelIds.isEmpty()">
              AND a.model_id IN
              <foreach collection="q.modelIds" item="id" open="(" separator="," close=")">#{id}</foreach>
            </if>
            """;
    String CODEBUDDY_APP = """
            <choose>
              <when test="!codeBuddyApps.isEmpty()">
                a.app_id IN <foreach collection="codeBuddyApps" item="id" open="(" separator="," close=")">#{id}</foreach>
              </when>
              <otherwise>1 = 0</otherwise>
            </choose>
            """;

    @Select("<script>SELECT a.model_id, " + AMOUNTS + " FROM mg_audit_log a " + FILTERS + """
            <if test="q.appId != null and q.appId != ''">AND a.app_id = #{q.appId}</if>
            GROUP BY a.model_id
            </script>
            """)
    List<ModelTotals> modelUsage(@Param("q") UsageQueryBO query);

    @Select("<script>SELECT a.model_id, p.person_um, a.codebuddy_user_id, " + AMOUNTS + """
             FROM mg_audit_log a
             LEFT JOIN mg_app p ON p.id = (
                SELECT MAX(pa.id) FROM mg_app pa
                WHERE pa.app_id = a.app_id AND pa.subject_type = 'PERSONAL'
             )
            """ + FILTERS + """
            <choose>
              <when test="q.source == 'personal-key'">AND p.person_um IS NOT NULL</when>
              <when test="q.source == 'codebuddy'">AND p.id IS NULL AND (""" + CODEBUDDY_APP + """
              )</when>
              <otherwise>AND (p.person_um IS NOT NULL OR (""" + CODEBUDDY_APP + """
              ))</otherwise>
            </choose>
            GROUP BY a.model_id, p.person_um, a.codebuddy_user_id
            </script>
            """)
    List<UserTotals> userUsage(@Param("q") UsageQueryBO query, @Param("codeBuddyApps") Set<String> codeBuddyApps);

    record ModelTotals(String modelId, long requests, long inputTokens, long outputTokens,
                       long upstreamTokens, long estimatedTokens, long missingTokenRequests) {
        public Amount amount() {
            return new Amount(requests, inputTokens, outputTokens, upstreamTokens, estimatedTokens, missingTokenRequests);
        }
    }

    record UserTotals(String modelId, String personUm, String codebuddyUserId, long requests, long inputTokens,
                      long outputTokens, long upstreamTokens, long estimatedTokens, long missingTokenRequests) {
        public Amount amount() {
            return new Amount(requests, inputTokens, outputTokens, upstreamTokens, estimatedTokens, missingTokenRequests);
        }
    }
}
