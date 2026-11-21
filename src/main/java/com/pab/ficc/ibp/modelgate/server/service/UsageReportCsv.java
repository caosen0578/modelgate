package com.pab.ficc.ibp.modelgate.server.service;

import com.pab.ficc.ibp.modelgate.server.domain.vo.UsageReportViews.Amount;
import com.pab.ficc.ibp.modelgate.server.domain.vo.UsageReportViews.Model;
import com.pab.ficc.ibp.modelgate.server.domain.vo.UsageReportViews.User;

import java.nio.charset.StandardCharsets;
import java.util.List;

final class UsageReportCsv {
    private static final String METRICS = ",请求数,输入 Token,输出 Token,总 Token,上游返回 Token,估算 Token,无 Token 请求\r\n";

    private UsageReportCsv() {}

    static byte[] models(List<Model> rows) {
        var csv = new StringBuilder("\uFEFF模型 ID").append(METRICS);
        for (Model row : rows) {
            csv.append(text(row.modelId()));
            appendAmount(csv, row.usage());
        }
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    static byte[] users(List<User> rows, boolean detail) {
        var csv = new StringBuilder(detail ? "\uFEFFUM,模型 ID,来源" : "\uFEFFUM").append(METRICS);
        for (User row : rows) {
            String um = text(row.um());
            if (detail) {
                row.models().forEach(model -> {
                    csv.append(um).append(',').append(text(model.modelId())).append(',')
                            .append(model.source().equals("codebuddy") ? "CodeBuddy" : "个人 Key");
                    appendAmount(csv, model.usage());
                });
            } else {
                csv.append(um);
                appendAmount(csv, row.usage());
            }
        }
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void appendAmount(StringBuilder csv, Amount row) {
        csv.append(',').append(row.requests()).append(',').append(row.inputTokens()).append(',').append(row.outputTokens())
                .append(',').append(row.getTotalTokens()).append(',').append(row.upstreamTokens())
                .append(',').append(row.estimatedTokens()).append(',').append(row.missingTokenRequests()).append("\r\n");
    }

    private static String text(String value) {
        String stripped = value.stripLeading();
        // 以文本打开可能被 Excel 识别为公式的标识，再按 CSV 规则转义双引号。
        boolean formula = !stripped.isEmpty() && "=+-@＝＋－＠".indexOf(stripped.charAt(0)) >= 0;
        return "\"" + (formula ? "'" : "") + value.replace("\"", "\"\"") + "\"";
    }
}
