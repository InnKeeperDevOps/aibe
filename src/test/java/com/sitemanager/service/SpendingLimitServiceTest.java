package com.sitemanager.service;

import com.sitemanager.model.SiteSettings;
import com.sitemanager.model.enums.CostResetPeriod;
import com.sitemanager.repository.ExpertReviewCostRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link SpendingLimitService}: budget refusal vs allowance, the
 * per-suggestion and global cap paths, and how the global reset period
 * influences the window the spend is measured against.
 */
class SpendingLimitServiceTest {

    private SiteSettingsService settingsService;
    private ExpertReviewCostRepository costRepository;
    private SiteSettings settings;
    private SpendingLimitService service;

    @BeforeEach
    void setUp() {
        settingsService = mock(SiteSettingsService.class);
        costRepository = mock(ExpertReviewCostRepository.class);
        settings = new SiteSettings();
        when(settingsService.getSettings()).thenReturn(settings);
        service = new SpendingLimitService(settingsService, costRepository);
    }

    @Test
    void allowsReviewWhenNoLimitsConfigured() {
        SpendingLimitService.LimitCheck check = service.checkCanStartReview(10L);

        assertThat(check.isAllowed()).isTrue();
        assertThat(check.getReason()).isNull();
        verify(costRepository, never()).sumCostBySuggestionId(any());
        verify(costRepository, never()).sumCostGlobal();
    }

    @Test
    void allowsReviewWhenPerSuggestionLimitNotReached() {
        settings.setMaxCostPerSuggestionUsd(new BigDecimal("5.00"));
        when(costRepository.sumCostBySuggestionId(10L)).thenReturn(new BigDecimal("2.50"));

        SpendingLimitService.LimitCheck check = service.checkCanStartReview(10L);

        assertThat(check.isAllowed()).isTrue();
    }

    @Test
    void refusesReviewWhenPerSuggestionSpendEqualsLimit() {
        settings.setMaxCostPerSuggestionUsd(new BigDecimal("5.00"));
        when(costRepository.sumCostBySuggestionId(10L)).thenReturn(new BigDecimal("5.00"));

        SpendingLimitService.LimitCheck check = service.checkCanStartReview(10L);

        assertThat(check.isAllowed()).isFalse();
        assertThat(check.getReason()).contains("spending limit");
        assertThat(check.getReason()).contains("this suggestion");
    }

    @Test
    void refusesReviewWhenPerSuggestionSpendExceedsLimit() {
        settings.setMaxCostPerSuggestionUsd(new BigDecimal("5.00"));
        when(costRepository.sumCostBySuggestionId(10L)).thenReturn(new BigDecimal("7.31"));

        SpendingLimitService.LimitCheck check = service.checkCanStartReview(10L);

        assertThat(check.isAllowed()).isFalse();
    }

    @Test
    void treatsNullPerSuggestionSpendAsZero() {
        settings.setMaxCostPerSuggestionUsd(new BigDecimal("5.00"));
        when(costRepository.sumCostBySuggestionId(10L)).thenReturn(null);

        SpendingLimitService.LimitCheck check = service.checkCanStartReview(10L);

        assertThat(check.isAllowed()).isTrue();
    }

    @Test
    void zeroOrNegativePerSuggestionLimitIsNotEnforced() {
        settings.setMaxCostPerSuggestionUsd(BigDecimal.ZERO);
        SpendingLimitService.LimitCheck check = service.checkCanStartReview(10L);
        assertThat(check.isAllowed()).isTrue();
        verify(costRepository, never()).sumCostBySuggestionId(any());

        settings.setMaxCostPerSuggestionUsd(new BigDecimal("-1"));
        check = service.checkCanStartReview(10L);
        assertThat(check.isAllowed()).isTrue();
    }

    @Test
    void allowsReviewWhenGlobalLimitNotReached_neverWindow() {
        settings.setMaxTotalCostUsd(new BigDecimal("100"));
        settings.setGlobalCostResetPeriod(CostResetPeriod.NEVER);
        when(costRepository.sumCostGlobal()).thenReturn(new BigDecimal("42"));

        SpendingLimitService.LimitCheck check = service.checkCanStartReview(10L);

        assertThat(check.isAllowed()).isTrue();
    }

    @Test
    void refusesReviewWhenGlobalSpendReachesLimit_neverWindow() {
        settings.setMaxTotalCostUsd(new BigDecimal("100"));
        settings.setGlobalCostResetPeriod(CostResetPeriod.NEVER);
        when(costRepository.sumCostGlobal()).thenReturn(new BigDecimal("100"));

        SpendingLimitService.LimitCheck check = service.checkCanStartReview(10L);

        assertThat(check.isAllowed()).isFalse();
        assertThat(check.getReason()).contains("overall");
    }

    @Test
    void dailyResetWindowQueriesFromStartOfUtcDay() {
        settings.setMaxTotalCostUsd(new BigDecimal("50"));
        settings.setGlobalCostResetPeriod(CostResetPeriod.DAILY);
        when(costRepository.sumCostSince(any())).thenReturn(new BigDecimal("10"));

        SpendingLimitService.LimitCheck check = service.checkCanStartReview(10L);

        assertThat(check.isAllowed()).isTrue();
        ArgumentCaptor<Instant> sinceCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(costRepository).sumCostSince(sinceCaptor.capture());
        Instant expectedStart = LocalDate.now(ZoneOffset.UTC)
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant();
        assertThat(sinceCaptor.getValue()).isEqualTo(expectedStart);
        verify(costRepository, never()).sumCostGlobal();
    }

    @Test
    void monthlyResetWindowQueriesFromStartOfUtcMonth() {
        settings.setMaxTotalCostUsd(new BigDecimal("50"));
        settings.setGlobalCostResetPeriod(CostResetPeriod.MONTHLY);
        when(costRepository.sumCostSince(any())).thenReturn(new BigDecimal("60"));

        SpendingLimitService.LimitCheck check = service.checkCanStartReview(10L);

        assertThat(check.isAllowed()).isFalse();
        ArgumentCaptor<Instant> sinceCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(costRepository).sumCostSince(sinceCaptor.capture());
        Instant expectedStart = YearMonth.now(ZoneOffset.UTC)
                .atDay(1)
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant();
        assertThat(sinceCaptor.getValue()).isEqualTo(expectedStart);
    }

    @Test
    void perSuggestionLimitCheckedBeforeGlobalLimit() {
        settings.setMaxCostPerSuggestionUsd(new BigDecimal("5"));
        settings.setMaxTotalCostUsd(new BigDecimal("100"));
        settings.setGlobalCostResetPeriod(CostResetPeriod.NEVER);
        when(costRepository.sumCostBySuggestionId(10L)).thenReturn(new BigDecimal("5"));

        SpendingLimitService.LimitCheck check = service.checkCanStartReview(10L);

        assertThat(check.isAllowed()).isFalse();
        assertThat(check.getReason()).contains("this suggestion");
        // Global cap should not even be consulted once per-suggestion fails.
        verify(costRepository, never()).sumCostGlobal();
    }

    @Test
    void nullSuggestionIdSkipsPerSuggestionCheckButStillEnforcesGlobal() {
        settings.setMaxCostPerSuggestionUsd(new BigDecimal("5"));
        settings.setMaxTotalCostUsd(new BigDecimal("100"));
        settings.setGlobalCostResetPeriod(CostResetPeriod.NEVER);
        when(costRepository.sumCostGlobal()).thenReturn(new BigDecimal("100"));

        SpendingLimitService.LimitCheck check = service.checkCanStartReview(null);

        assertThat(check.isAllowed()).isFalse();
        assertThat(check.getReason()).contains("overall");
        verify(costRepository, never()).sumCostBySuggestionId(any());
    }

    @Test
    void getGlobalSpendInWindow_returnsZeroWhenLedgerEmpty() {
        settings.setGlobalCostResetPeriod(CostResetPeriod.NEVER);
        when(costRepository.sumCostGlobal()).thenReturn(null);

        BigDecimal spend = service.getGlobalSpendInWindow();

        assertThat(spend).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void getGlobalSpendInWindow_returnsSumFromRepository() {
        settings.setGlobalCostResetPeriod(CostResetPeriod.NEVER);
        when(costRepository.sumCostGlobal()).thenReturn(new BigDecimal("123.45"));

        BigDecimal spend = service.getGlobalSpendInWindow();

        assertThat(spend).isEqualByComparingTo("123.45");
    }

    @Test
    void getGlobalSpendInWindow_dailyDelegatesToSinceQuery() {
        settings.setGlobalCostResetPeriod(CostResetPeriod.DAILY);
        when(costRepository.sumCostSince(any())).thenReturn(new BigDecimal("9.99"));

        BigDecimal spend = service.getGlobalSpendInWindow();

        assertThat(spend).isEqualByComparingTo("9.99");
        verify(costRepository, never()).sumCostGlobal();
    }

    @Test
    void limitCheckAllowedFactoryReturnsAllowed() {
        SpendingLimitService.LimitCheck check = SpendingLimitService.LimitCheck.allowed();
        assertThat(check.isAllowed()).isTrue();
        assertThat(check.getReason()).isNull();
        assertThat(check.getLimitType()).isNull();
        assertThat(check.getCurrentSpend()).isNull();
        assertThat(check.getLimitAmount()).isNull();
        assertThat(check.getResetPeriod()).isNull();
        assertThat(check.getResetsAt()).isNull();
    }

    @Test
    void limitCheckRefusedFactoryCarriesReason() {
        SpendingLimitService.LimitCheck check =
                SpendingLimitService.LimitCheck.refused("too expensive");
        assertThat(check.isAllowed()).isFalse();
        assertThat(check.getReason()).isEqualTo("too expensive");
    }

    // -------------------------------------------------------------------------
    // Task 5 — user-facing refusal messages must explain which limit was hit,
    // what the current spend is, and how/when it will free up again.
    // -------------------------------------------------------------------------

    @Test
    void perSuggestionRefusal_messageIncludesCurrentSpendAndLimit() {
        settings.setMaxCostPerSuggestionUsd(new BigDecimal("5.00"));
        when(costRepository.sumCostBySuggestionId(10L))
                .thenReturn(new BigDecimal("5.3245"));

        SpendingLimitService.LimitCheck check = service.checkCanStartReview(10L);

        assertThat(check.isAllowed()).isFalse();
        // Both spend and limit must appear, formatted as dollars to 2 decimals
        // so users see real numbers, not raw decimals.
        assertThat(check.getReason()).contains("$5.32");
        assertThat(check.getReason()).contains("$5.00");
        assertThat(check.getReason()).contains("this suggestion");
        assertThat(check.getReason()).contains("spending limit");
        // Must explain how the cap will free up.
        assertThat(check.getReason()).contains("administrator");
    }

    @Test
    void perSuggestionRefusal_structuredFieldsPopulated() {
        settings.setMaxCostPerSuggestionUsd(new BigDecimal("5.00"));
        when(costRepository.sumCostBySuggestionId(10L))
                .thenReturn(new BigDecimal("5.32"));

        SpendingLimitService.LimitCheck check = service.checkCanStartReview(10L);

        assertThat(check.getLimitType())
                .isEqualTo(SpendingLimitService.LimitType.PER_SUGGESTION);
        assertThat(check.getCurrentSpend()).isEqualByComparingTo("5.32");
        assertThat(check.getLimitAmount()).isEqualByComparingTo("5.00");
        assertThat(check.getResetPeriod()).isNull();
        assertThat(check.getResetsAt()).isNull();
    }

    @Test
    void globalNeverRefusal_messageExplainsAdminUnlockOnly() {
        settings.setMaxTotalCostUsd(new BigDecimal("100"));
        settings.setGlobalCostResetPeriod(CostResetPeriod.NEVER);
        when(costRepository.sumCostGlobal()).thenReturn(new BigDecimal("100.05"));

        SpendingLimitService.LimitCheck check = service.checkCanStartReview(10L);

        assertThat(check.isAllowed()).isFalse();
        assertThat(check.getReason()).contains("overall");
        assertThat(check.getReason()).contains("spending limit");
        assertThat(check.getReason()).contains("$100.05");
        assertThat(check.getReason()).contains("$100.00");
        // NEVER must point at an admin, never at an automatic reset.
        assertThat(check.getReason()).contains("administrator");
        assertThat(check.getReason()).doesNotContainIgnoringCase("daily");
        assertThat(check.getReason()).doesNotContainIgnoringCase("monthly");
    }

    @Test
    void globalNeverRefusal_structuredFieldsPopulated() {
        settings.setMaxTotalCostUsd(new BigDecimal("100"));
        settings.setGlobalCostResetPeriod(CostResetPeriod.NEVER);
        when(costRepository.sumCostGlobal()).thenReturn(new BigDecimal("100.05"));

        SpendingLimitService.LimitCheck check = service.checkCanStartReview(10L);

        assertThat(check.getLimitType()).isEqualTo(SpendingLimitService.LimitType.GLOBAL);
        assertThat(check.getCurrentSpend()).isEqualByComparingTo("100.05");
        assertThat(check.getLimitAmount()).isEqualByComparingTo("100");
        assertThat(check.getResetPeriod()).isEqualTo(CostResetPeriod.NEVER);
        // NEVER doesn't have a future reset moment.
        assertThat(check.getResetsAt()).isNull();
    }

    @Test
    void globalDailyRefusal_messageExplainsMidnightUtcReset() {
        settings.setMaxTotalCostUsd(new BigDecimal("50"));
        settings.setGlobalCostResetPeriod(CostResetPeriod.DAILY);
        when(costRepository.sumCostSince(any())).thenReturn(new BigDecimal("50.10"));

        SpendingLimitService.LimitCheck check = service.checkCanStartReview(10L);

        assertThat(check.isAllowed()).isFalse();
        assertThat(check.getReason()).contains("overall");
        assertThat(check.getReason()).contains("daily");
        assertThat(check.getReason()).contains("$50.10");
        assertThat(check.getReason()).contains("$50.00");
        assertThat(check.getReason()).contains("midnight");
        assertThat(check.getReason()).contains("UTC");
        assertThat(check.getReason()).contains("administrator");
    }

    @Test
    void globalDailyRefusal_resetsAtNextUtcMidnight() {
        settings.setMaxTotalCostUsd(new BigDecimal("50"));
        settings.setGlobalCostResetPeriod(CostResetPeriod.DAILY);
        when(costRepository.sumCostSince(any())).thenReturn(new BigDecimal("50"));

        SpendingLimitService.LimitCheck check = service.checkCanStartReview(10L);

        Instant expectedReset = LocalDate.now(ZoneOffset.UTC)
                .plusDays(1)
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant();
        assertThat(check.getResetsAt()).isEqualTo(expectedReset);
        assertThat(check.getResetPeriod()).isEqualTo(CostResetPeriod.DAILY);
        assertThat(check.getLimitType()).isEqualTo(SpendingLimitService.LimitType.GLOBAL);
    }

    @Test
    void globalMonthlyRefusal_messageExplainsNextMonthReset() {
        settings.setMaxTotalCostUsd(new BigDecimal("100"));
        settings.setGlobalCostResetPeriod(CostResetPeriod.MONTHLY);
        when(costRepository.sumCostSince(any())).thenReturn(new BigDecimal("123.45"));

        SpendingLimitService.LimitCheck check = service.checkCanStartReview(10L);

        assertThat(check.isAllowed()).isFalse();
        assertThat(check.getReason()).contains("overall");
        assertThat(check.getReason()).contains("monthly");
        assertThat(check.getReason()).contains("$123.45");
        assertThat(check.getReason()).contains("$100.00");
        assertThat(check.getReason()).contains("next month");
        assertThat(check.getReason()).contains("administrator");
    }

    @Test
    void globalMonthlyRefusal_resetsAtStartOfNextMonth() {
        settings.setMaxTotalCostUsd(new BigDecimal("100"));
        settings.setGlobalCostResetPeriod(CostResetPeriod.MONTHLY);
        when(costRepository.sumCostSince(any())).thenReturn(new BigDecimal("100"));

        SpendingLimitService.LimitCheck check = service.checkCanStartReview(10L);

        Instant expectedReset = YearMonth.now(ZoneOffset.UTC)
                .plusMonths(1)
                .atDay(1)
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant();
        assertThat(check.getResetsAt()).isEqualTo(expectedReset);
        assertThat(check.getResetPeriod()).isEqualTo(CostResetPeriod.MONTHLY);
    }

    @Test
    void perSuggestionRefusal_messageNeverHasFractionalCentsExposed() {
        // Even when raw spend has many decimal places, the user-facing
        // message rounds to plain dollars-and-cents — no "$5.324567" leaking.
        settings.setMaxCostPerSuggestionUsd(new BigDecimal("5"));
        when(costRepository.sumCostBySuggestionId(10L))
                .thenReturn(new BigDecimal("5.001234"));

        SpendingLimitService.LimitCheck check = service.checkCanStartReview(10L);

        // Whatever value is shown, it should look like $X.XX (two decimals).
        assertThat(check.getReason()).doesNotContain("5.001234");
        assertThat(check.getReason()).contains("$5.00");
    }

    @Test
    void messageNeverPromisesAutoResetForNeverPeriod() {
        // Users on the NEVER window must not be told to "wait for reset" —
        // only an admin can unlock the platform again.
        settings.setMaxTotalCostUsd(new BigDecimal("10"));
        settings.setGlobalCostResetPeriod(CostResetPeriod.NEVER);
        when(costRepository.sumCostGlobal()).thenReturn(new BigDecimal("10"));

        SpendingLimitService.LimitCheck check = service.checkCanStartReview(10L);

        assertThat(check.getReason()).doesNotContain("midnight");
        assertThat(check.getReason()).doesNotContain("next month");
    }

    @Test
    void refusalMessageIsAlwaysWellFormedSentence() {
        // Sanity: messages start with a capital, end with a period, no
        // doubled spaces.
        settings.setMaxCostPerSuggestionUsd(new BigDecimal("1"));
        when(costRepository.sumCostBySuggestionId(10L)).thenReturn(new BigDecimal("2"));
        String reason = service.checkCanStartReview(10L).getReason();
        assertThat(reason).startsWith("A ");
        assertThat(reason).endsWith(".");
        assertThat(reason).doesNotContain("  ");
    }
}
