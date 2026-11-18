package com.pab.ficc.ibp.modelgate.server.common;

import com.pab.ficc.ibp.modelgate.server.domain.vo.AuditLogVO;
import com.pab.ficc.ibp.modelgate.server.domain.vo.PayloadListVO;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import reactor.core.publisher.Flux;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 每批从游标之后读取，输出与列表相同的信息，避免把全部记录同时驻留内存。 */
public final class RecordCsv {
    public static final int BATCH_SIZE = 200;
    public static final String AUDIT_HEADER = "时间,TraceID,应用 appId,应用编码,UM,姓名,流量类型,模型,服务编码,客户端 IP,状态,延迟毫秒,输入 Token,输出 Token,Token 来源,请求路径,错误";
    public static final String PAYLOAD_HEADER = "时间,TraceID,应用 appId,应用编码,UM,姓名,流量类型,模型,服务编码,流式,上游状态,结束原因,完整性,输入 Token,输出 Token,Token 来源,审计匹配数,请求头长度,请求长度,响应长度,请求头预览,请求预览,响应预览";

    private RecordCsv() {}

    public static String row(Object... values) { return cells(values); }

    public static String audit(AuditLogVO row) {
        return cells(row.getCreateTime(), row.getTraceId(), row.getAppId(), row.getAppCode(), row.getUm(),
                row.getPersonName(), row.getTrafficType(), row.getModelId(), row.getServiceId(), row.getClientIp(),
                row.getHttpStatus(), row.getLatencyMs(), row.getInputTokens(), row.getOutputTokens(), row.getTokenSource(),
                row.getRequestPath(), row.getErrorMsg());
    }

    public static String payload(PayloadListVO row) {
        boolean ambiguous = row.getAuditMatchCount() != null && row.getAuditMatchCount() > 1;
        return cells(row.getCreateTime(), row.getTraceId(), row.getAppId(), row.getAppCode(), row.getUm(),
                row.getPersonName(), row.getTrafficType(), row.getModelId(), row.getServiceId(), row.getIsStream(),
                row.getUpstreamStatus(), row.getFinishReason(), row.getTruncated(),
                ambiguous ? null : row.getInputTokens(), ambiguous ? null : row.getOutputTokens(),
                ambiguous ? "存疑" : row.getTokenSource(), row.getAuditMatchCount(), row.getRequestHeadersLen(),
                row.getRequestLen(), row.getResponseTextLen(), row.getRequestHeadersPreview(),
                row.getRequestPreview(), row.getResponsePreview());
    }

    public static <T, C> ResponseEntity<Flux<byte[]>> response(String filename, String header,
            Function<C, List<T>> fetch, Function<T, C> id, Function<T, String> render) {
        Flux<byte[]> body = Flux.<byte[], C>generate(() -> null, (before, sink) -> {
            List<T> batch = fetch.apply(before);
            if (batch.isEmpty()) {
                sink.complete();
                return before;
            }
            sink.next(batch.stream().map(render).collect(Collectors.joining()).getBytes(StandardCharsets.UTF_8));
            return id.apply(batch.get(batch.size() - 1));
        }).subscribeOn(AdminScheduler.get());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .headers(h -> h.setContentDisposition(ContentDisposition.attachment().filename(filename).build()))
                .body(Flux.concat(Flux.just(("\uFEFF" + header + "\r\n").getBytes(StandardCharsets.UTF_8)), body));
    }

    private static String cells(Object... values) {
        return Arrays.stream(values).map(RecordCsv::cell).collect(Collectors.joining(",")) + "\r\n";
    }

    private static String cell(Object value) {
        if (value == null) return "";
        if (value instanceof Number || value instanceof Boolean) return value.toString();
        String text = value.toString();
        String stripped = text.stripLeading();
        boolean formula = !stripped.isEmpty() && "=+-@＝＋－＠".indexOf(stripped.charAt(0)) >= 0;
        return "\"" + (formula ? "'" : "") + text.replace("\"", "\"\"") + "\"";
    }
}
