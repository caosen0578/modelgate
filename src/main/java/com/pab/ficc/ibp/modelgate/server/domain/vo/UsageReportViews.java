package com.pab.ficc.ibp.modelgate.server.domain.vo;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public final class UsageReportViews {
    private UsageReportViews() {}

    public record Amount(long requests, long inputTokens, long outputTokens, long upstreamTokens,
                         long estimatedTokens, long missingTokenRequests) {
        public long getTotalTokens() { return inputTokens + outputTokens; }

        public Amount plus(Amount other) {
            return new Amount(requests + other.requests, inputTokens + other.inputTokens, outputTokens + other.outputTokens,
                    upstreamTokens + other.upstreamTokens, estimatedTokens + other.estimatedTokens,
                    missingTokenRequests + other.missingTokenRequests);
        }
    }

    public record Model(String modelId, Amount usage) {}
    public record Breakdown(String modelId, String source, Amount usage) {}
    public record User(String um, Amount usage, List<Breakdown> models) {}
    public record UnassignedReason(String reason, long requests, long tokens) {}
    public record Day(LocalDate date, long requests, long tokens) {}
    public record Application(String appId, long tokens) {}
    public record Comparison(LocalDateTime startTime, LocalDateTime endTime, long requests, long tokens,
                             long activeCount, long activeApplications, List<Day> days) {}
    public record Dashboard(LocalDateTime startTime, LocalDateTime endTime, List<Day> days, long activeApplications,
                            List<Application> applications, Comparison previous) {}
    public record Report<T>(List<T> items, int total, Amount totals, boolean codeBuddyMappingConfigured,
                            long unassignedRequests, long unassignedTokens, List<UnassignedReason> unassignedReasons,
                            @JsonInclude(JsonInclude.Include.NON_NULL) Dashboard dashboard) {}
}
