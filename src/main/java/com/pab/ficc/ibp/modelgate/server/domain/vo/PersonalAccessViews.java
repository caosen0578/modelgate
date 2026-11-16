package com.pab.ficc.ibp.modelgate.server.domain.vo;

import com.pab.ficc.ibp.modelgate.server.domain.entity.AccessRuleEntity;
import com.pab.ficc.ibp.modelgate.server.domain.entity.AppInfoEntity;
import com.pab.ficc.ibp.modelgate.server.domain.entity.ModelInstanceEntity;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** 个人接入独立出参；内部 appId、系统兼容字段、上游配置不暴露。 */
public final class PersonalAccessViews {
    private PersonalAccessViews() {}

    public record Meta(boolean enabled) {}

    public record Profile(String um, String name, String remark, boolean enabled,
                          LocalDateTime createTime, LocalDateTime updateTime, Long authCount, Long credCount) {
        public static Profile of(AppInfoEntity app) {
            return of(app, null, null);
        }

        public static Profile of(AppInfoEntity app, Long authCount, Long credCount) {
            return new Profile(app.getPersonUm(), app.getAppName(), app.getRemark(),
                    Boolean.TRUE.equals(app.getEnabled()), app.getCreateTime(), app.getUpdateTime(), authCount, credCount);
        }
    }

    public record IssuedKey(Long id, String apiKey, LocalDateTime expiresAt) {}

    public record SourceRule(Long id, String ruleType, String ruleValue, String remark) {
        public static SourceRule of(AccessRuleEntity rule) {
            return new SourceRule(rule.getId(), rule.getRuleType(), rule.getRuleValue(), rule.getRemark());
        }
    }

    public record BatchItem(String um, String modelId, String status, String message) {}
    public record BatchResult(int added, int skipped, int failed, List<BatchItem> items) {
        public static BatchResult of(List<BatchItem> items) {
            int added = 0;
            int skipped = 0;
            for (BatchItem item : items) {
                if ("ADDED".equals(item.status())) added++;
                if ("SKIPPED".equals(item.status())) skipped++;
            }
            return new BatchResult(added, skipped, items.size() - added - skipped, items);
        }
    }

    public record ModelOption(String modelId, String modelName, String modelType) {
        public static ModelOption of(ModelInstanceEntity model) {
            return new ModelOption(model.getModelId(), model.getModelName(), model.getModelType());
        }
    }

    public record Grant(String modelId, String modelName, String modelType, boolean enabled,
                        Integer qpsLimit, Integer dailyLimit, Long monthlyTokens, LocalDateTime expiresAt) {
        public static Grant of(AppModelAuthVO auth) {
            return new Grant(auth.getModelId(), auth.getModelName(), auth.getModelType(),
                    Boolean.TRUE.equals(auth.getEnabled()), auth.getQpsLimit(), auth.getDailyLimit(),
                    auth.getMonthlyTokens(), auth.getExpiresAt());
        }
    }

    public record Totals(long requests, long inputTokens, long outputTokens, long totalTokens,
                         long upstreamTokens, long estimatedTokens) {
        public static Totals of(long requests, long input, long output, long upstream, long estimated) {
            return new Totals(requests, input, output, input + output, upstream, estimated);
        }

        public Totals plus(Totals other) {
            return of(requests + other.requests, inputTokens + other.inputTokens,
                    outputTokens + other.outputTokens, upstreamTokens + other.upstreamTokens,
                    estimatedTokens + other.estimatedTokens);
        }
    }

    public record ModelUsage(String modelId, Totals totals) {}

    public record Usage(String um, String name, LocalDate start, LocalDate end,
                        Totals totals, List<ModelUsage> models) {}
}
