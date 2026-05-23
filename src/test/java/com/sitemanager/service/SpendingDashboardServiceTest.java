package com.sitemanager.service;

import com.sitemanager.dto.GlobalCapStatusDto;
import com.sitemanager.dto.RecentReviewDto;
import com.sitemanager.dto.SpendingDashboardDto;
import com.sitemanager.dto.SpendingPeriodArchiveDto;
import com.sitemanager.dto.SpendingTrendPointDto;
import com.sitemanager.dto.TopSpendingSuggestionDto;
import com.sitemanager.model.ExpertReviewCost;
import com.sitemanager.model.SiteSettings;
import com.sitemanager.model.SpendingPeriodArchive;
import com.sitemanager.model.Suggestion;
import com.sitemanager.model.enums.CostResetPeriod;
import com.sitemanager.repository.ExpertReviewCostRepository;
import com.sitemanager.repository.SuggestionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link SpendingDashboardService}: the dashboard payload
 * assembly, including the global cap status, the top-spending suggestions
 * list, the per-day trend with zero-filled gaps, the recent reviews feed,
 * and the title sanitization that protects every consumer of the payload.
 */
class SpendingDashboardServiceTest {

    private ExpertReviewCostRepository costRepository;
    private SuggestionRepository suggestionRepository;
    private SiteSettingsService settingsService;
    private SpendingPeriodArchiveService periodArchiveService;
    private SiteSettings settings;
    private SpendingDashboardService service;

    @BeforeEach
    void setUp() {
        costRepository = mock(ExpertReviewCostRepository.class);
        suggestionRepository = mock(SuggestionRepository.class);
        settingsService = mock(SiteSettingsService.class);
        periodArchiveService = mock(SpendingPeriodArchiveService.class);
        settings = new SiteSettings();
        when(settingsService.getSettings()).thenReturn(settings);
        when(costRepository.findSuggestionSpendTotalsOrderedDesc()).thenReturn(List.of());
        when(costRepository.findByCreatedAtGreaterThanEqualOrderByCreatedAtAsc(any()))
                .thenReturn(List.of());
        when(costRepository.findTop20ByOrderByCreatedAtDesc()).thenReturn(List.of());
        when(suggestionRepository.findAllById(anyIterable())).thenReturn(List.of());
        when(periodArchiveService.getDailyHistory()).thenReturn(List.of());
        when(periodArchiveService.getMonthlyHistory()).thenReturn(List.of());
        service = new SpendingDashboardService(
                costRepository, suggestionRepository, settingsService, periodArchiveService);
    }

    @Test
    void globalCap_unlimitedWhenNoLimitConfigured() {
        when(costRepository.sumCostGlobal()).thenReturn(new BigDecimal("12.34"));

        SpendingDashboardDto dashboard = service.buildDashboard();
        GlobalCapStatusDto cap = dashboard.getGlobalCap();

        assertThat(cap.isLimitConfigured()).isFalse();
        assertThat(cap.getCurrentSpendUsd()).isEqualByComparingTo("12.34");
        assertThat(cap.getLimitUsd()).isNull();
        assertThat(cap.getRemainingBudgetUsd()).isNull();
        assertThat(cap.getPercentUsed()).isNull();
        assertThat(cap.getDisplayLimit()).isEqualTo("No limit");
        assertThat(cap.getDisplayRemaining()).isEqualTo("Unlimited");
        assertThat(cap.getResetPeriod()).isEqualTo(CostResetPeriod.NEVER);
        assertThat(cap.getNextResetAt()).isNull();
    }

    @Test
    void globalCap_zeroOrNegativeLimitTreatedAsUnlimited() {
        settings.setMaxTotalCostUsd(BigDecimal.ZERO);
        when(costRepository.sumCostGlobal()).thenReturn(new BigDecimal("5"));

        GlobalCapStatusDto cap = service.buildDashboard().getGlobalCap();

        assertThat(cap.isLimitConfigured()).isFalse();
    }

    @Test
    void globalCap_enforcedNeverWindowComputesRemainingAndPercent() {
        settings.setMaxTotalCostUsd(new BigDecimal("100"));
        settings.setGlobalCostResetPeriod(CostResetPeriod.NEVER);
        when(costRepository.sumCostGlobal()).thenReturn(new BigDecimal("25"));

        GlobalCapStatusDto cap = service.buildDashboard().getGlobalCap();

        assertThat(cap.isLimitConfigured()).isTrue();
        assertThat(cap.getCurrentSpendUsd()).isEqualByComparingTo("25");
        assertThat(cap.getLimitUsd()).isEqualByComparingTo("100");
        assertThat(cap.getRemainingBudgetUsd()).isEqualByComparingTo("75");
        assertThat(cap.getPercentUsed()).isEqualTo(25);
        assertThat(cap.getDisplayCurrentSpend()).isEqualTo("$25.00");
        assertThat(cap.getDisplayLimit()).isEqualTo("$100.00");
        assertThat(cap.getDisplayRemaining()).isEqualTo("$75.00");
        assertThat(cap.getResetPeriod()).isEqualTo(CostResetPeriod.NEVER);
        assertThat(cap.getNextResetAt()).isNull();
    }

    @Test
    void globalCap_overshootReportedAsZeroRemainingNotNegative() {
        settings.setMaxTotalCostUsd(new BigDecimal("10"));
        when(costRepository.sumCostGlobal()).thenReturn(new BigDecimal("12.50"));

        GlobalCapStatusDto cap = service.buildDashboard().getGlobalCap();

        assertThat(cap.getRemainingBudgetUsd()).isEqualByComparingTo("0");
        assertThat(cap.getDisplayRemaining()).isEqualTo("$0.00");
        assertThat(cap.getPercentUsed()).isEqualTo(125);
    }

    @Test
    void globalCap_dailyWindowUsesStartOfUtcDayAndReportsNextReset() {
        settings.setMaxTotalCostUsd(new BigDecimal("50"));
        settings.setGlobalCostResetPeriod(CostResetPeriod.DAILY);
        when(costRepository.sumCostSince(any())).thenReturn(new BigDecimal("9.99"));

        GlobalCapStatusDto cap = service.buildDashboard().getGlobalCap();

        assertThat(cap.getResetPeriod()).isEqualTo(CostResetPeriod.DAILY);
        Instant expectedStart = LocalDate.now(ZoneOffset.UTC)
                .atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant expectedReset = LocalDate.now(ZoneOffset.UTC)
                .plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        assertThat(cap.getWindowStart()).isEqualTo(expectedStart);
        assertThat(cap.getNextResetAt()).isEqualTo(expectedReset);
        verify(costRepository, never()).sumCostGlobal();
    }

    @Test
    void globalCap_monthlyWindowUsesStartOfUtcMonth() {
        settings.setMaxTotalCostUsd(new BigDecimal("50"));
        settings.setGlobalCostResetPeriod(CostResetPeriod.MONTHLY);
        when(costRepository.sumCostSince(any())).thenReturn(new BigDecimal("20"));

        GlobalCapStatusDto cap = service.buildDashboard().getGlobalCap();

        assertThat(cap.getResetPeriod()).isEqualTo(CostResetPeriod.MONTHLY);
        Instant expectedStart = YearMonth.now(ZoneOffset.UTC)
                .atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant expectedReset = YearMonth.now(ZoneOffset.UTC)
                .plusMonths(1).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        assertThat(cap.getWindowStart()).isEqualTo(expectedStart);
        assertThat(cap.getNextResetAt()).isEqualTo(expectedReset);
    }

    @Test
    void topSuggestions_returnsOrderedAndAttachesSanitizedTitles() {
        when(costRepository.findSuggestionSpendTotalsOrderedDesc()).thenReturn(List.of(
                new Object[]{1L, new BigDecimal("5.00"), 4L},
                new Object[]{2L, new BigDecimal("2.50"), 2L},
                new Object[]{3L, new BigDecimal("0.75"), 1L}
        ));
        Suggestion s1 = suggestion(1L, "Speed up homepage");
        Suggestion s2 = suggestion(2L, "Add <script>alert(1)</script> support");
        Suggestion s3 = suggestion(3L, "Fix login");
        when(suggestionRepository.findAllById(anyIterable()))
                .thenReturn(List.of(s1, s2, s3));

        SpendingDashboardDto dashboard = service.buildDashboard();
        List<TopSpendingSuggestionDto> top = dashboard.getTopSuggestions();

        assertThat(top).hasSize(3);
        assertThat(top.get(0).getSuggestionId()).isEqualTo(1L);
        assertThat(top.get(0).getReviewCount()).isEqualTo(4L);
        assertThat(top.get(0).getTotalCostUsd()).isEqualByComparingTo("5.00");
        assertThat(top.get(0).getDisplayCostUsd()).isEqualTo("$5.00");
        assertThat(top.get(0).getTitle()).isEqualTo("Speed up homepage");

        assertThat(top.get(1).getTitle())
                .doesNotContain("<script>")
                .contains("&lt;script&gt;")
                .contains("&lt;/script&gt;");
    }

    @Test
    void topSuggestions_clampedToConfiguredLimit() {
        List<Object[]> rows = new ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            rows.add(new Object[]{(long) i, new BigDecimal(i + ".00"), 1L});
        }
        when(costRepository.findSuggestionSpendTotalsOrderedDesc()).thenReturn(rows);

        SpendingDashboardDto dashboard = service.buildDashboard(30, 5);

        assertThat(dashboard.getTopSuggestions()).hasSize(5);
    }

    @Test
    void topSuggestions_carriesPerSuggestionCapWhenConfigured() {
        settings.setMaxCostPerSuggestionUsd(new BigDecimal("10"));
        when(costRepository.findSuggestionSpendTotalsOrderedDesc()).thenReturn(List.of(
                new Object[]{1L, new BigDecimal("6.50"), 3L}
        ));
        when(suggestionRepository.findAllById(anyIterable()))
                .thenReturn(List.of(suggestion(1L, "Hello")));

        List<TopSpendingSuggestionDto> top = service.buildDashboard().getTopSuggestions();

        assertThat(top).hasSize(1);
        TopSpendingSuggestionDto row = top.get(0);
        assertThat(row.getPerSuggestionLimitUsd()).isEqualByComparingTo("10");
        assertThat(row.getRemainingBudgetUsd()).isEqualByComparingTo("3.50");
        assertThat(row.getDisplayRemaining()).isEqualTo("$3.50");
        assertThat(row.getPercentUsed()).isEqualTo(65);
    }

    @Test
    void topSuggestions_missingTitleFallsBackToPlaceholder() {
        when(costRepository.findSuggestionSpendTotalsOrderedDesc()).thenReturn(List.of(
                new Object[]{99L, new BigDecimal("1.00"), 1L}
        ));
        when(suggestionRepository.findAllById(anyIterable())).thenReturn(List.of());

        List<TopSpendingSuggestionDto> top = service.buildDashboard().getTopSuggestions();

        assertThat(top).hasSize(1);
        assertThat(top.get(0).getTitle()).isEqualTo("(suggestion not found)");
    }

    @Test
    void topSuggestions_emptyLedgerYieldsEmptyList() {
        when(costRepository.findSuggestionSpendTotalsOrderedDesc()).thenReturn(List.of());

        assertThat(service.buildDashboard().getTopSuggestions()).isEmpty();
    }

    @Test
    void trend_defaultWindowProducesOnePointPerDay() {
        SpendingDashboardDto dashboard = service.buildDashboard();

        assertThat(dashboard.getTrendWindowDays())
                .isEqualTo(SpendingDashboardService.DEFAULT_TREND_WINDOW_DAYS);
        assertThat(dashboard.getTrend())
                .hasSize(SpendingDashboardService.DEFAULT_TREND_WINDOW_DAYS);
        for (SpendingTrendPointDto point : dashboard.getTrend()) {
            assertThat(point.getTotalCostUsd()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(point.getReviewCount()).isZero();
            assertThat(point.getDate()).isNotNull();
        }
    }

    @Test
    void trend_bucketsReviewsByUtcDay() {
        Instant today = LocalDate.now(ZoneOffset.UTC).atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant yesterday = LocalDate.now(ZoneOffset.UTC).minusDays(1)
                .atStartOfDay(ZoneOffset.UTC).toInstant().plusSeconds(60);

        List<ExpertReviewCost> rows = List.of(
                review(1L, "QA", new BigDecimal("1.50"), today.plusSeconds(120)),
                review(1L, "Security", new BigDecimal("0.75"), today.plusSeconds(300)),
                review(2L, "QA", new BigDecimal("0.25"), yesterday)
        );
        when(costRepository.findByCreatedAtGreaterThanEqualOrderByCreatedAtAsc(any()))
                .thenReturn(rows);

        List<SpendingTrendPointDto> trend = service.buildDashboard(7, 5).getTrend();

        assertThat(trend).hasSize(7);
        SpendingTrendPointDto last = trend.get(trend.size() - 1);
        SpendingTrendPointDto secondToLast = trend.get(trend.size() - 2);
        assertThat(last.getDate()).isEqualTo(LocalDate.now(ZoneOffset.UTC));
        assertThat(last.getTotalCostUsd()).isEqualByComparingTo("2.25");
        assertThat(last.getReviewCount()).isEqualTo(2L);
        assertThat(secondToLast.getDate()).isEqualTo(LocalDate.now(ZoneOffset.UTC).minusDays(1));
        assertThat(secondToLast.getTotalCostUsd()).isEqualByComparingTo("0.25");
        assertThat(secondToLast.getReviewCount()).isEqualTo(1L);
    }

    @Test
    void trend_clampsWindowSizeToMaximum() {
        SpendingDashboardDto dashboard = service.buildDashboard(10_000, 5);

        assertThat(dashboard.getTrendWindowDays())
                .isEqualTo(SpendingDashboardService.MAX_TREND_WINDOW_DAYS);
        assertThat(dashboard.getTrend())
                .hasSize(SpendingDashboardService.MAX_TREND_WINDOW_DAYS);
    }

    @Test
    void trend_nonPositiveWindowFallsBackToDefault() {
        SpendingDashboardDto dashboard = service.buildDashboard(-5, 0);

        assertThat(dashboard.getTrendWindowDays())
                .isEqualTo(SpendingDashboardService.DEFAULT_TREND_WINDOW_DAYS);
    }

    @Test
    void recentReviews_attachesSanitizedTitles() {
        Instant now = Instant.now();
        ExpertReviewCost r = review(10L, "QA Engineer",
                new BigDecimal("0.1234"), now);
        when(costRepository.findTop20ByOrderByCreatedAtDesc()).thenReturn(List.of(r));
        when(suggestionRepository.findAllById(anyIterable()))
                .thenReturn(List.of(suggestion(10L, "Add  newlines\n\tand   spaces")));

        List<RecentReviewDto> feed = service.buildDashboard().getRecentReviews();

        assertThat(feed).hasSize(1);
        RecentReviewDto entry = feed.get(0);
        assertThat(entry.getSuggestionId()).isEqualTo(10L);
        assertThat(entry.getSuggestionTitle()).isEqualTo("Add newlines and spaces");
        assertThat(entry.getExpertName()).isEqualTo("QA Engineer");
        assertThat(entry.getCostUsd()).isEqualByComparingTo("0.1234");
        assertThat(entry.getDisplayCostUsd()).isEqualTo("$0.1234");
        assertThat(entry.getTotalTokens()).isEqualTo(40L);
    }

    @Test
    void recentReviews_emptyLedgerReturnsEmptyList() {
        when(costRepository.findTop20ByOrderByCreatedAtDesc()).thenReturn(List.of());

        assertThat(service.buildDashboard().getRecentReviews()).isEmpty();
    }

    @Test
    void sanitize_escapesHtmlSpecialsAndTrimsTrailingWhitespace() {
        String dangerous = "<img src=x onerror=\"alert('xss')\"> & friends ";

        String safe = SpendingDashboardService.sanitize(dangerous);

        assertThat(safe).doesNotContain("<img");
        assertThat(safe).contains("&lt;img");
        assertThat(safe).contains("&amp;");
        assertThat(safe).contains("&quot;");
        assertThat(safe).contains("&#39;");
        assertThat(safe).doesNotEndWith(" ");
    }

    @Test
    void sanitize_truncatesOverLongInputWithEllipsis() {
        String huge = "a".repeat(SpendingDashboardService.MAX_TITLE_LENGTH + 50);

        String safe = SpendingDashboardService.sanitize(huge);

        assertThat(safe).endsWith("…");
        assertThat(safe.replace("…", ""))
                .hasSize(SpendingDashboardService.MAX_TITLE_LENGTH);
    }

    @Test
    void sanitize_nullInputBecomesEmptyString() {
        assertThat(SpendingDashboardService.sanitize(null)).isEmpty();
    }

    @Test
    void buildDashboard_carriesGeneratedAtAndWindowSize() {
        SpendingDashboardDto dashboard = service.buildDashboard(14, 7);

        assertThat(dashboard.getGeneratedAt()).isNotNull();
        assertThat(dashboard.getTrendWindowDays()).isEqualTo(14);
    }

    @Test
    void buildDashboard_triggersPeriodArchivalSoLatestRolloverShowsImmediately() {
        service.buildDashboard();

        verify(periodArchiveService).archiveIfPeriodRolledOver();
        verify(periodArchiveService).getDailyHistory();
        verify(periodArchiveService).getMonthlyHistory();
    }

    @Test
    void buildDashboard_includesDailyAndMonthlyHistoryFromArchive() {
        Instant todayStart = LocalDate.now(ZoneOffset.UTC)
                .atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant yesterdayStart = todayStart.minusSeconds(86400);
        SpendingPeriodArchive daily = new SpendingPeriodArchive(
                CostResetPeriod.DAILY, yesterdayStart, todayStart,
                new BigDecimal("4.25"), 3L, new BigDecimal("10.00"));
        SpendingPeriodArchiveDto dailyDto = SpendingPeriodArchiveDto.fromEntity(daily);
        when(periodArchiveService.getDailyHistory()).thenReturn(List.of(dailyDto));

        Instant monthStart = YearMonth.now(ZoneOffset.UTC)
                .atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant priorMonthStart = YearMonth.now(ZoneOffset.UTC)
                .minusMonths(1).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        SpendingPeriodArchive monthly = new SpendingPeriodArchive(
                CostResetPeriod.MONTHLY, priorMonthStart, monthStart,
                new BigDecimal("87.10"), 22L, new BigDecimal("200.00"));
        SpendingPeriodArchiveDto monthlyDto = SpendingPeriodArchiveDto.fromEntity(monthly);
        when(periodArchiveService.getMonthlyHistory()).thenReturn(List.of(monthlyDto));

        SpendingDashboardDto dashboard = service.buildDashboard();

        assertThat(dashboard.getDailyHistory()).hasSize(1);
        assertThat(dashboard.getDailyHistory().get(0).getTotalCostUsd())
                .isEqualByComparingTo("4.25");
        assertThat(dashboard.getMonthlyHistory()).hasSize(1);
        assertThat(dashboard.getMonthlyHistory().get(0).getTotalReviews())
                .isEqualTo(22L);
    }

    @Test
    void buildDashboard_keepsRenderingEvenIfArchivalFails() {
        when(periodArchiveService.archiveIfPeriodRolledOver())
                .thenThrow(new RuntimeException("simulated archive failure"));
        when(periodArchiveService.getDailyHistory())
                .thenThrow(new RuntimeException("simulated history failure"));
        when(periodArchiveService.getMonthlyHistory())
                .thenThrow(new RuntimeException("simulated history failure"));

        SpendingDashboardDto dashboard = service.buildDashboard();

        assertThat(dashboard).isNotNull();
        assertThat(dashboard.getDailyHistory()).isEmpty();
        assertThat(dashboard.getMonthlyHistory()).isEmpty();
    }

    private static Suggestion suggestion(Long id, String title) {
        Suggestion s = new Suggestion();
        s.setId(id);
        s.setTitle(title);
        return s;
    }

    private static ExpertReviewCost review(Long suggestionId, String expert,
                                           BigDecimal cost, Instant createdAt) {
        ExpertReviewCost r = new ExpertReviewCost();
        r.setSuggestionId(suggestionId);
        r.setExpertName(expert);
        r.setCostUsd(cost);
        r.setInputTokens(10);
        r.setOutputTokens(20);
        r.setCacheReadInputTokens(5);
        r.setCacheCreationInputTokens(5);
        r.setDurationMs(500);
        r.setCreatedAt(createdAt);
        return r;
    }
}
