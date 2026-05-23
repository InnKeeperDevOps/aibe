package com.sitemanager.dto;

import java.time.Instant;
import java.util.List;

/**
 * The full payload behind the admin spending dashboard: the live status of
 * the global spend cap, the suggestions consuming the most budget right
 * now, a per-day trend of spending over a recent window, a feed of the
 * most recent recorded reviews, and frozen summaries of completed daily
 * and monthly windows that have already rolled over.
 *
 * <p>Every list is non-null (empty when there is no data) so the
 * dashboard renderer never has to defend against {@code null}.
 */
public class SpendingDashboardDto {

    private GlobalCapStatusDto globalCap;
    private List<TopSpendingSuggestionDto> topSuggestions;
    private List<SpendingTrendPointDto> trend;
    private List<RecentReviewDto> recentReviews;
    private List<SpendingPeriodArchiveDto> dailyHistory;
    private List<SpendingPeriodArchiveDto> monthlyHistory;
    private int trendWindowDays;
    private Instant generatedAt;

    public SpendingDashboardDto() {}

    public SpendingDashboardDto(GlobalCapStatusDto globalCap,
                                List<TopSpendingSuggestionDto> topSuggestions,
                                List<SpendingTrendPointDto> trend,
                                List<RecentReviewDto> recentReviews,
                                int trendWindowDays,
                                Instant generatedAt) {
        this(globalCap, topSuggestions, trend, recentReviews,
                List.of(), List.of(), trendWindowDays, generatedAt);
    }

    public SpendingDashboardDto(GlobalCapStatusDto globalCap,
                                List<TopSpendingSuggestionDto> topSuggestions,
                                List<SpendingTrendPointDto> trend,
                                List<RecentReviewDto> recentReviews,
                                List<SpendingPeriodArchiveDto> dailyHistory,
                                List<SpendingPeriodArchiveDto> monthlyHistory,
                                int trendWindowDays,
                                Instant generatedAt) {
        this.globalCap = globalCap;
        this.topSuggestions = topSuggestions != null ? topSuggestions : List.of();
        this.trend = trend != null ? trend : List.of();
        this.recentReviews = recentReviews != null ? recentReviews : List.of();
        this.dailyHistory = dailyHistory != null ? dailyHistory : List.of();
        this.monthlyHistory = monthlyHistory != null ? monthlyHistory : List.of();
        this.trendWindowDays = trendWindowDays;
        this.generatedAt = generatedAt;
    }

    public GlobalCapStatusDto getGlobalCap() { return globalCap; }
    public void setGlobalCap(GlobalCapStatusDto v) { this.globalCap = v; }

    public List<TopSpendingSuggestionDto> getTopSuggestions() { return topSuggestions; }
    public void setTopSuggestions(List<TopSpendingSuggestionDto> v) { this.topSuggestions = v; }

    public List<SpendingTrendPointDto> getTrend() { return trend; }
    public void setTrend(List<SpendingTrendPointDto> v) { this.trend = v; }

    public List<RecentReviewDto> getRecentReviews() { return recentReviews; }
    public void setRecentReviews(List<RecentReviewDto> v) { this.recentReviews = v; }

    public List<SpendingPeriodArchiveDto> getDailyHistory() { return dailyHistory; }
    public void setDailyHistory(List<SpendingPeriodArchiveDto> v) {
        this.dailyHistory = v != null ? v : List.of();
    }

    public List<SpendingPeriodArchiveDto> getMonthlyHistory() { return monthlyHistory; }
    public void setMonthlyHistory(List<SpendingPeriodArchiveDto> v) {
        this.monthlyHistory = v != null ? v : List.of();
    }

    public int getTrendWindowDays() { return trendWindowDays; }
    public void setTrendWindowDays(int v) { this.trendWindowDays = v; }

    public Instant getGeneratedAt() { return generatedAt; }
    public void setGeneratedAt(Instant v) { this.generatedAt = v; }
}
