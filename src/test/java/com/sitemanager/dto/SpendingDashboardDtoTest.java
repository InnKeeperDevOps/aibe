package com.sitemanager.dto;

import com.sitemanager.model.enums.CostResetPeriod;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the spending dashboard DTOs: factory defaults, formatting,
 * overshoot handling, and per-item budget math.
 */
class SpendingDashboardDtoTest {

    @Test
    void globalCapUnlimited_carriesFriendlyDefaults() {
        GlobalCapStatusDto cap = GlobalCapStatusDto.unlimited(
                new BigDecimal("3.14"), CostResetPeriod.NEVER,
                Instant.EPOCH, null);

        assertThat(cap.isLimitConfigured()).isFalse();
        assertThat(cap.getCurrentSpendUsd()).isEqualByComparingTo("3.14");
        assertThat(cap.getLimitUsd()).isNull();
        assertThat(cap.getRemainingBudgetUsd()).isNull();
        assertThat(cap.getPercentUsed()).isNull();
        assertThat(cap.getDisplayLimit()).isEqualTo("No limit");
        assertThat(cap.getDisplayRemaining()).isEqualTo("Unlimited");
        assertThat(cap.getDisplayCurrentSpend()).isEqualTo("$3.14");
    }

    @Test
    void globalCapUnlimited_handlesNullSpendAsZero() {
        GlobalCapStatusDto cap = GlobalCapStatusDto.unlimited(
                null, CostResetPeriod.DAILY, Instant.EPOCH, Instant.EPOCH);

        assertThat(cap.getCurrentSpendUsd()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(cap.getDisplayCurrentSpend()).isEqualTo("$0.00");
    }

    @Test
    void globalCapEnforced_computesPercentAndRemaining() {
        GlobalCapStatusDto cap = GlobalCapStatusDto.enforced(
                new BigDecimal("4"), new BigDecimal("10"),
                CostResetPeriod.MONTHLY, Instant.EPOCH, Instant.EPOCH);

        assertThat(cap.isLimitConfigured()).isTrue();
        assertThat(cap.getPercentUsed()).isEqualTo(40);
        assertThat(cap.getRemainingBudgetUsd()).isEqualByComparingTo("6");
        assertThat(cap.getDisplayRemaining()).isEqualTo("$6.00");
    }

    @Test
    void globalCapEnforced_overshootShowsZeroRemaining() {
        GlobalCapStatusDto cap = GlobalCapStatusDto.enforced(
                new BigDecimal("15"), new BigDecimal("10"),
                CostResetPeriod.NEVER, Instant.EPOCH, null);

        assertThat(cap.getRemainingBudgetUsd()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(cap.getDisplayRemaining()).isEqualTo("$0.00");
        assertThat(cap.getPercentUsed()).isEqualTo(150);
    }

    @Test
    void topSpendingSuggestion_withoutCapHasNoRemainingFields() {
        TopSpendingSuggestionDto row = new TopSpendingSuggestionDto(
                1L, "Hello", 2L, new BigDecimal("0.50"), null);

        assertThat(row.getDisplayCostUsd()).isEqualTo("$0.50");
        assertThat(row.getPerSuggestionLimitUsd()).isNull();
        assertThat(row.getRemainingBudgetUsd()).isNull();
        assertThat(row.getDisplayRemaining()).isNull();
        assertThat(row.getPercentUsed()).isNull();
    }

    @Test
    void topSpendingSuggestion_withCapComputesRemainingAndPercent() {
        TopSpendingSuggestionDto row = new TopSpendingSuggestionDto(
                1L, "Hello", 2L, new BigDecimal("7.50"), new BigDecimal("10"));

        assertThat(row.getRemainingBudgetUsd()).isEqualByComparingTo("2.50");
        assertThat(row.getDisplayRemaining()).isEqualTo("$2.50");
        assertThat(row.getPercentUsed()).isEqualTo(75);
    }

    @Test
    void topSpendingSuggestion_zeroOrNegativeLimitIgnored() {
        TopSpendingSuggestionDto row = new TopSpendingSuggestionDto(
                1L, "Hello", 2L, new BigDecimal("1"), BigDecimal.ZERO);

        assertThat(row.getRemainingBudgetUsd()).isNull();
        assertThat(row.getPercentUsed()).isNull();
    }

    @Test
    void topSpendingSuggestion_overshootShowsZeroRemaining() {
        TopSpendingSuggestionDto row = new TopSpendingSuggestionDto(
                1L, "Hello", 5L, new BigDecimal("12"), new BigDecimal("10"));

        assertThat(row.getRemainingBudgetUsd()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(row.getDisplayRemaining()).isEqualTo("$0.00");
        assertThat(row.getPercentUsed()).isEqualTo(120);
    }

    @Test
    void trendPoint_carriesDateCountAndDisplayCost() {
        SpendingTrendPointDto point = new SpendingTrendPointDto(
                LocalDate.of(2025, 1, 15), new BigDecimal("1.234"), 3L);

        assertThat(point.getDate()).isEqualTo(LocalDate.of(2025, 1, 15));
        assertThat(point.getReviewCount()).isEqualTo(3L);
        assertThat(point.getTotalCostUsd()).isEqualByComparingTo("1.234");
        assertThat(point.getDisplayCostUsd()).isEqualTo("$1.23");
    }

    @Test
    void trendPoint_handlesNullCostAsZero() {
        SpendingTrendPointDto point = new SpendingTrendPointDto(
                LocalDate.of(2025, 1, 15), null, 0L);

        assertThat(point.getTotalCostUsd()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(point.getDisplayCostUsd()).isEqualTo("$0.00");
    }

    @Test
    void recentReview_formatsCostWithFourDecimalPlaces() {
        Instant now = Instant.parse("2025-01-15T00:00:00Z");
        RecentReviewDto entry = new RecentReviewDto(
                1L, 2L, "Title", "QA Engineer",
                100L, new BigDecimal("0.0123"), 500L, now);

        assertThat(entry.getDisplayCostUsd()).isEqualTo("$0.0123");
        assertThat(entry.getCreatedAt()).isEqualTo(now);
    }

    @Test
    void recentReview_handlesNullCostAsZero() {
        RecentReviewDto entry = new RecentReviewDto(
                1L, 2L, "Title", "QA Engineer",
                100L, null, 500L, Instant.EPOCH);

        assertThat(entry.getCostUsd()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(entry.getDisplayCostUsd()).isEqualTo("$0.0000");
    }

    @Test
    void dashboard_normalizesNullListsToEmpty() {
        SpendingDashboardDto dashboard = new SpendingDashboardDto(
                null, null, null, null, 30, Instant.EPOCH);

        assertThat(dashboard.getTopSuggestions()).isEmpty();
        assertThat(dashboard.getTrend()).isEmpty();
        assertThat(dashboard.getRecentReviews()).isEmpty();
    }

    @Test
    void dashboard_passesThroughGeneratedAtAndWindowSize() {
        Instant now = Instant.now();
        SpendingDashboardDto dashboard = new SpendingDashboardDto(
                GlobalCapStatusDto.unlimited(BigDecimal.ZERO,
                        CostResetPeriod.NEVER, Instant.EPOCH, null),
                List.of(), List.of(), List.of(), 14, now);

        assertThat(dashboard.getTrendWindowDays()).isEqualTo(14);
        assertThat(dashboard.getGeneratedAt()).isEqualTo(now);
    }
}
