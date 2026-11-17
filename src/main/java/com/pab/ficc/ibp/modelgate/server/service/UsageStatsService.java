package com.pab.ficc.ibp.modelgate.server.service;

import com.pab.ficc.ibp.modelgate.server.common.AppSubject;
import com.pab.ficc.ibp.modelgate.server.common.BusinessException;
import com.pab.ficc.ibp.modelgate.server.common.ErrorCode;
import com.pab.ficc.ibp.modelgate.server.common.PageSupport;
import com.pab.ficc.ibp.modelgate.server.domain.bo.UsageQueryBO;
import com.pab.ficc.ibp.modelgate.server.domain.vo.UsageReportViews.Amount;
import com.pab.ficc.ibp.modelgate.server.domain.vo.UsageReportViews.Breakdown;
import com.pab.ficc.ibp.modelgate.server.domain.vo.UsageReportViews.Model;
import com.pab.ficc.ibp.modelgate.server.domain.vo.UsageReportViews.Report;
import com.pab.ficc.ibp.modelgate.server.domain.vo.UsageReportViews.User;
import com.pab.ficc.ibp.modelgate.server.mapper.UsageStatsMapper;
import com.pab.ficc.ibp.modelgate.server.mapper.UsageStatsMapper.UserTotals;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class UsageStatsService {
    private static final Amount ZERO = new Amount(0, 0, 0, 0, 0, 0);
    private static final String PERSONAL_KEY = "personal-key";
    private static final String CODEBUDDY = "codebuddy";
    private final UsageStatsMapper mapper;
    private final CodeBuddyUsageIdentity identities;

    public Report<Model> modelUsage(UsageQueryBO query) {
        validate(query);
        var rows = mapper.modelUsage(query).stream()
                .map(row -> new Model(row.modelId(), row.amount()))
                .sorted(Comparator.<Model>comparingLong(row -> row.usage().getTotalTokens()).reversed()
                        .thenComparing(Model::modelId))
                .toList();
        var totals = rows.stream().map(Model::usage).reduce(ZERO, Amount::plus);
        return report(query, rows, totals);
    }

    public Report<User> userUsage(UsageQueryBO query) {
        validate(query);
        String selectedUm = query.getUm() == null || query.getUm().isBlank() ? null : AppSubject.normalizeUm(query.getUm());
        if (query.getSource() != null && !query.getSource().isBlank()
                && !Set.of(PERSONAL_KEY, CODEBUDDY).contains(query.getSource())) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "人员用量来源不正确");
        }

        var groups = mapper.userUsage(query, identities.appIds());
        var codeBuddyUsers = identities.resolve(groups.stream().filter(row -> row.personUm() == null)
                .map(UserTotals::codebuddyUserId).filter(Objects::nonNull).distinct().toList());
        Map<String, Map<BreakdownKey, Amount>> users = new HashMap<>();
        for (var group : groups) {
            boolean personalKey = group.personUm() != null;
            String um = personalKey ? group.personUm().toUpperCase(Locale.ROOT)
                    : (group.codebuddyUserId() == null ? null : codeBuddyUsers.get(group.codebuddyUserId()));
            if (selectedUm != null && !selectedUm.equals(um)) continue;
            var key = new BreakdownKey(group.modelId(), personalKey ? PERSONAL_KEY : CODEBUDDY);
            users.computeIfAbsent(um, ignored -> new HashMap<>()).merge(key, group.amount(), Amount::plus);
        }

        var rows = users.entrySet().stream().map(entry -> user(entry.getKey(), entry.getValue()))
                .sorted(Comparator.<User>comparingLong(row -> row.usage().getTotalTokens()).reversed()
                        .thenComparing(User::um, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
        return report(query, rows, rows.stream().map(User::usage).reduce(ZERO, Amount::plus));
    }

    private static User user(String um, Map<BreakdownKey, Amount> groups) {
        var models = groups.entrySet().stream()
                .map(entry -> new Breakdown(entry.getKey().modelId(), entry.getKey().source(), entry.getValue()))
                .sorted(Comparator.comparing(Breakdown::modelId).thenComparing(Breakdown::source))
                .toList();
        return new User(um, models.stream().map(Breakdown::usage).reduce(ZERO, Amount::plus), models);
    }

    private <T> Report<T> report(UsageQueryBO query, List<T> rows, Amount totals) {
        long page = query.getPageIndex() - 1;
        long size = query.getPageSize();
        int first = page > rows.size() / size ? rows.size() : (int) (page * size);
        int last = (int) Math.min(rows.size(), first + size);
        return new Report<>(rows.subList(first, last), rows.size(), totals, identities.mappingConfigured());
    }

    private static void validate(UsageQueryBO query) {
        PageSupport.validate(query, "用量统计");
        LocalDateTime now = LocalDateTime.now();
        if (query.getStartTime() == null) query.setStartTime(now.toLocalDate().minusDays(30).atStartOfDay());
        if (query.getEndTime() == null) query.setEndTime(now);
        if (query.getStartTime().isAfter(query.getEndTime())) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "统计开始时间必须早于或等于结束时间");
        }
    }

    private record BreakdownKey(String modelId, String source) {}
}
