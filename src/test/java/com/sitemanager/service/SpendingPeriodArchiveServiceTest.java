package com.sitemanager.service;

import com.sitemanager.dto.SpendingPeriodArchiveDto;
import com.sitemanager.model.ExpertReviewCost;
import com.sitemanager.model.SiteSettings;
import com.sitemanager.model.SpendingPeriodArchive;
import com.sitemanager.model.enums.CostResetPeriod;
import com.sitemanager.repository.ExpertReviewCostRepository;
import com.sitemanager.repository.SpendingPeriodArchiveRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link SpendingPeriodArchiveService}: when the daily / monthly
 * reset window rolls over, the service writes a frozen summary of the
 * prior window so it remains visible in history, and the new window
 * starts at zero so newly allowed work can proceed unimpeded. The check
 * is idempotent, walks every missing window in order, and never raises an
 * exception that would disrupt callers.
 */
class SpendingPeriodArchiveServiceTest {

    private ExpertReviewCostRepository costRepository;
    private SpendingPeriodArchiveRepository archiveRepository;
    private SiteSettingsService settingsService;
    private SiteSettings settings;
    private SpendingPeriodArchiveService service;
    private Instant now;

    @BeforeEach
    void setUp() {
        costRepository = mock(ExpertReviewCostRepository.class);
        archiveRepository = mock(SpendingPeriodArchiveRepository.class);
        settingsService = mock(SiteSettingsService.class);
        settings = new SiteSettings();
        when(settingsService.getSettings()).thenReturn(settings);

        // Pick a deterministic "now" — mid-afternoon UTC on a recent date so
        // the period containing it is unambiguous.
        now = LocalDateTime.of(2026, 5, 23, 15, 30)
                .toInstant(ZoneOffset.UTC);
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);
        service = new SpendingPeriodArchiveService(
                costRepository, archiveRepository, settingsService, clock);

        when(archiveRepository.findFirstByPeriodTypeOrderByPeriodStartDesc(any()))
                .thenReturn(Optional.empty());
        when(costRepository.findFirstByOrderByCreatedAtAsc()).thenReturn(Optional.empty());
        when(archiveRepository.existsByPeriodTypeAndPeriodStart(any(), any())).thenReturn(false);
        when(costRepository.sumCostBetween(any(), any())).thenReturn(null);
        when(costRepository.countBetween(any(), any())).thenReturn(0L);
        when(archiveRepository.save(any(SpendingPeriodArchive.class)))
                .thenAnswer(i -> i.getArgument(0));
    }

    @Test
    void archiveIfPeriodRolledOver_doesNothingWhenLedgerIsEmptyAndNoPriorArchives() {
        int written = service.archiveIfPeriodRolledOver();

        assertThat(written).isZero();
        verify(archiveRepository, never()).save(any(SpendingPeriodArchive.class));
    }

    @Test
    void archiveIfPeriodRolledOver_writesYesterdayWhenOldestCostIsYesterday() {
        Instant yesterday = LocalDate.of(2026, 5, 22)
                .atStartOfDay(ZoneOffset.UTC).toInstant().plusSeconds(60);
        when(costRepository.findFirstByOrderByCreatedAtAsc())
                .thenReturn(Optional.of(reviewAt(yesterday)));
        when(costRepository.sumCostBetween(any(), any()))
                .thenReturn(new BigDecimal("3.50"));
        when(costRepository.countBetween(any(), any())).thenReturn(4L);

        int written = service.archiveIfPeriodRolledOver();

        // One daily archive (yesterday) and no monthly archives (still in the
        // same calendar month as now).
        assertThat(written).isEqualTo(1);
        ArgumentCaptor<SpendingPeriodArchive> captor =
                ArgumentCaptor.forClass(SpendingPeriodArchive.class);
        verify(archiveRepository, atLeastOnce()).save(captor.capture());
        SpendingPeriodArchive saved = captor.getAllValues().get(0);
        assertThat(saved.getPeriodType()).isEqualTo(CostResetPeriod.DAILY);
        assertThat(saved.getPeriodStart()).isEqualTo(
                LocalDate.of(2026, 5, 22).atStartOfDay(ZoneOffset.UTC).toInstant());
        assertThat(saved.getPeriodEnd()).isEqualTo(
                LocalDate.of(2026, 5, 23).atStartOfDay(ZoneOffset.UTC).toInstant());
        assertThat(saved.getTotalCostUsd()).isEqualByComparingTo("3.50");
        assertThat(saved.getTotalReviews()).isEqualTo(4L);
    }

    @Test
    void archiveIfPeriodRolledOver_capturesGlobalLimitInEffectAtArchiveTime() {
        settings.setMaxTotalCostUsd(new BigDecimal("42.00"));
        Instant yesterday = LocalDate.of(2026, 5, 22)
                .atStartOfDay(ZoneOffset.UTC).toInstant().plusSeconds(60);
        when(costRepository.findFirstByOrderByCreatedAtAsc())
                .thenReturn(Optional.of(reviewAt(yesterday)));

        service.archiveIfPeriodRolledOver();

        ArgumentCaptor<SpendingPeriodArchive> captor =
                ArgumentCaptor.forClass(SpendingPeriodArchive.class);
        verify(archiveRepository, atLeastOnce()).save(captor.capture());
        assertThat(captor.getAllValues().get(0).getLimitAtPeriodEnd())
                .isEqualByComparingTo("42.00");
    }

    @Test
    void archiveIfPeriodRolledOver_walksMultipleMissingDailyWindowsInOrder() {
        Instant fiveDaysAgo = LocalDate.of(2026, 5, 18)
                .atStartOfDay(ZoneOffset.UTC).toInstant().plusSeconds(120);
        when(costRepository.findFirstByOrderByCreatedAtAsc())
                .thenReturn(Optional.of(reviewAt(fiveDaysAgo)));
        when(costRepository.sumCostBetween(any(), any()))
                .thenReturn(new BigDecimal("1.00"));
        when(costRepository.countBetween(any(), any())).thenReturn(1L);

        int written = service.archiveIfPeriodRolledOver();

        // 5 completed daily windows: May 18, 19, 20, 21, 22. May 23 is
        // current and never archived.
        assertThat(written).isEqualTo(5);
        ArgumentCaptor<SpendingPeriodArchive> captor =
                ArgumentCaptor.forClass(SpendingPeriodArchive.class);
        verify(archiveRepository, times(5)).save(captor.capture());
        List<SpendingPeriodArchive> all = captor.getAllValues();
        assertThat(all.get(0).getPeriodStart()).isEqualTo(
                LocalDate.of(2026, 5, 18).atStartOfDay(ZoneOffset.UTC).toInstant());
        assertThat(all.get(4).getPeriodStart()).isEqualTo(
                LocalDate.of(2026, 5, 22).atStartOfDay(ZoneOffset.UTC).toInstant());
        for (SpendingPeriodArchive entry : all) {
            assertThat(entry.getPeriodType()).isEqualTo(CostResetPeriod.DAILY);
        }
    }

    @Test
    void archiveIfPeriodRolledOver_resumesFromLastArchivedDay() {
        // Latest archive captured May 21 → next walk should pick up May 22
        // (only the missing day before today) and stop there.
        SpendingPeriodArchive last = new SpendingPeriodArchive(
                CostResetPeriod.DAILY,
                LocalDate.of(2026, 5, 21).atStartOfDay(ZoneOffset.UTC).toInstant(),
                LocalDate.of(2026, 5, 22).atStartOfDay(ZoneOffset.UTC).toInstant(),
                new BigDecimal("0.50"), 1L, null);
        when(archiveRepository.findFirstByPeriodTypeOrderByPeriodStartDesc(CostResetPeriod.DAILY))
                .thenReturn(Optional.of(last));

        int written = service.archiveIfPeriodRolledOver();

        ArgumentCaptor<SpendingPeriodArchive> captor =
                ArgumentCaptor.forClass(SpendingPeriodArchive.class);
        verify(archiveRepository, atLeastOnce()).save(captor.capture());
        List<SpendingPeriodArchive> savedDaily = captor.getAllValues().stream()
                .filter(a -> a.getPeriodType() == CostResetPeriod.DAILY)
                .toList();
        assertThat(savedDaily).hasSize(1);
        assertThat(savedDaily.get(0).getPeriodStart()).isEqualTo(
                LocalDate.of(2026, 5, 22).atStartOfDay(ZoneOffset.UTC).toInstant());
        // and at least one row was written
        assertThat(written).isGreaterThanOrEqualTo(1);
    }

    @Test
    void archiveIfPeriodRolledOver_doesNothingWhenCurrentDayIsAlreadyCovered() {
        // Latest archive ends exactly at the start of today → nothing to do.
        SpendingPeriodArchive last = new SpendingPeriodArchive(
                CostResetPeriod.DAILY,
                LocalDate.of(2026, 5, 22).atStartOfDay(ZoneOffset.UTC).toInstant(),
                LocalDate.of(2026, 5, 23).atStartOfDay(ZoneOffset.UTC).toInstant(),
                new BigDecimal("0.50"), 1L, null);
        when(archiveRepository.findFirstByPeriodTypeOrderByPeriodStartDesc(CostResetPeriod.DAILY))
                .thenReturn(Optional.of(last));
        // Pretend the monthly latest is also up to date.
        SpendingPeriodArchive monthly = new SpendingPeriodArchive(
                CostResetPeriod.MONTHLY,
                YearMonth.of(2026, 5).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant(),
                YearMonth.of(2026, 6).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant(),
                BigDecimal.ZERO, 0L, null);
        when(archiveRepository.findFirstByPeriodTypeOrderByPeriodStartDesc(CostResetPeriod.MONTHLY))
                .thenReturn(Optional.of(monthly));

        int written = service.archiveIfPeriodRolledOver();

        assertThat(written).isZero();
        verify(archiveRepository, never()).save(any(SpendingPeriodArchive.class));
    }

    @Test
    void archiveIfPeriodRolledOver_writesMonthlyArchiveWhenPriorMonthHasCosts() {
        // First cost is in April 2026 → April is a completed monthly window.
        Instant aprilCost = LocalDateTime.of(2026, 4, 10, 9, 0)
                .toInstant(ZoneOffset.UTC);
        when(costRepository.findFirstByOrderByCreatedAtAsc())
                .thenReturn(Optional.of(reviewAt(aprilCost)));
        when(costRepository.sumCostBetween(any(), any())).thenReturn(new BigDecimal("9.99"));
        when(costRepository.countBetween(any(), any())).thenReturn(8L);

        service.archiveIfPeriodRolledOver();

        ArgumentCaptor<SpendingPeriodArchive> captor =
                ArgumentCaptor.forClass(SpendingPeriodArchive.class);
        verify(archiveRepository, atLeastOnce()).save(captor.capture());
        List<SpendingPeriodArchive> monthly = captor.getAllValues().stream()
                .filter(a -> a.getPeriodType() == CostResetPeriod.MONTHLY)
                .toList();
        assertThat(monthly).hasSize(1);
        SpendingPeriodArchive april = monthly.get(0);
        assertThat(april.getPeriodStart()).isEqualTo(
                YearMonth.of(2026, 4).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant());
        assertThat(april.getPeriodEnd()).isEqualTo(
                YearMonth.of(2026, 5).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant());
    }

    @Test
    void archiveIfPeriodRolledOver_skipsRowAlreadyPresent() {
        Instant yesterday = LocalDate.of(2026, 5, 22)
                .atStartOfDay(ZoneOffset.UTC).toInstant().plusSeconds(60);
        when(costRepository.findFirstByOrderByCreatedAtAsc())
                .thenReturn(Optional.of(reviewAt(yesterday)));
        // Pretend the daily row was inserted by a concurrent invocation.
        when(archiveRepository.existsByPeriodTypeAndPeriodStart(
                eq(CostResetPeriod.DAILY),
                eq(LocalDate.of(2026, 5, 22).atStartOfDay(ZoneOffset.UTC).toInstant())))
                .thenReturn(true);

        int written = service.archiveIfPeriodRolledOver();

        assertThat(written).isZero();
        verify(archiveRepository, never()).save(any(SpendingPeriodArchive.class));
    }

    @Test
    void archiveIfPeriodRolledOver_swallowsConcurrentDuplicateInsert() {
        Instant yesterday = LocalDate.of(2026, 5, 22)
                .atStartOfDay(ZoneOffset.UTC).toInstant().plusSeconds(60);
        when(costRepository.findFirstByOrderByCreatedAtAsc())
                .thenReturn(Optional.of(reviewAt(yesterday)));
        when(archiveRepository.save(any(SpendingPeriodArchive.class)))
                .thenThrow(new DataIntegrityViolationException("dup"));

        // Even though the save fails as a duplicate, the call must return
        // cleanly so the caller (dashboard fetch) is not disrupted.
        int written = service.archiveIfPeriodRolledOver();

        assertThat(written).isZero();
    }

    @Test
    void archiveIfPeriodRolledOver_swallowsRepositoryException() {
        when(archiveRepository.findFirstByPeriodTypeOrderByPeriodStartDesc(any()))
                .thenThrow(new RuntimeException("db down"));

        // Both daily and monthly paths are wrapped — the method must return
        // 0 rather than propagate the failure.
        int written = service.archiveIfPeriodRolledOver();

        assertThat(written).isZero();
    }

    @Test
    void archiveIfPeriodRolledOver_walkCapBoundsCatchUpWork() {
        // Pretend the earliest cost is so far back that a naive walk would
        // exceed the cap. The walk should stop at the cap and leave the
        // remainder for the next invocation.
        Instant longAgo = LocalDate.of(2024, 1, 1)
                .atStartOfDay(ZoneOffset.UTC).toInstant().plusSeconds(60);
        when(costRepository.findFirstByOrderByCreatedAtAsc())
                .thenReturn(Optional.of(reviewAt(longAgo)));
        when(costRepository.sumCostBetween(any(), any())).thenReturn(new BigDecimal("0.01"));

        int written = service.archiveIfPeriodRolledOver();

        assertThat(written).isLessThanOrEqualTo(
                SpendingPeriodArchiveService.MAX_PERIODS_ARCHIVED_PER_INVOCATION * 2);
        // It should still have written something on this run.
        assertThat(written).isGreaterThan(0);
    }

    @Test
    void getDailyHistory_returnsArchivesAsDtosNewestFirst() {
        Instant d1 = LocalDate.of(2026, 5, 20).atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant d2 = LocalDate.of(2026, 5, 21).atStartOfDay(ZoneOffset.UTC).toInstant();
        SpendingPeriodArchive a1 = new SpendingPeriodArchive(
                CostResetPeriod.DAILY, d1, d1.plusSeconds(86400),
                new BigDecimal("1.00"), 1L, null);
        a1.setId(1L);
        SpendingPeriodArchive a2 = new SpendingPeriodArchive(
                CostResetPeriod.DAILY, d2, d2.plusSeconds(86400),
                new BigDecimal("2.00"), 3L, null);
        a2.setId(2L);
        // Repository returns newest first.
        when(archiveRepository.findTop90ByPeriodTypeOrderByPeriodStartDesc(CostResetPeriod.DAILY))
                .thenReturn(List.of(a2, a1));

        List<SpendingPeriodArchiveDto> result = service.getDailyHistory();

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getId()).isEqualTo(2L);
        assertThat(result.get(1).getId()).isEqualTo(1L);
        assertThat(result.get(0).getPeriodLabel()).isEqualTo("2026-05-21");
    }

    @Test
    void getMonthlyHistory_returnsEmptyListWhenNoArchives() {
        when(archiveRepository.findTop90ByPeriodTypeOrderByPeriodStartDesc(CostResetPeriod.MONTHLY))
                .thenReturn(List.of());

        assertThat(service.getMonthlyHistory()).isEmpty();
    }

    @Test
    void periodStartContaining_dailyTruncatesToStartOfUtcDay() {
        Instant midDay = LocalDateTime.of(2026, 5, 23, 14, 27)
                .toInstant(ZoneOffset.UTC);

        Instant start = SpendingPeriodArchiveService
                .periodStartContaining(CostResetPeriod.DAILY, midDay);

        assertThat(start).isEqualTo(LocalDate.of(2026, 5, 23)
                .atTime(LocalTime.MIDNIGHT)
                .toInstant(ZoneOffset.UTC));
    }

    @Test
    void periodStartContaining_monthlyTruncatesToFirstOfMonthUtc() {
        Instant mid = LocalDateTime.of(2026, 5, 23, 14, 27)
                .toInstant(ZoneOffset.UTC);

        Instant start = SpendingPeriodArchiveService
                .periodStartContaining(CostResetPeriod.MONTHLY, mid);

        assertThat(start).isEqualTo(YearMonth.of(2026, 5)
                .atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant());
    }

    @Test
    void nextPeriodStart_dailyAdvancesByOneDay() {
        Instant start = LocalDate.of(2026, 5, 23)
                .atStartOfDay(ZoneOffset.UTC).toInstant();

        Instant next = SpendingPeriodArchiveService
                .nextPeriodStart(CostResetPeriod.DAILY, start);

        assertThat(next).isEqualTo(LocalDate.of(2026, 5, 24)
                .atStartOfDay(ZoneOffset.UTC).toInstant());
    }

    @Test
    void nextPeriodStart_monthlyAdvancesToFirstOfNextMonth() {
        Instant start = YearMonth.of(2026, 5).atDay(1)
                .atStartOfDay(ZoneOffset.UTC).toInstant();

        Instant next = SpendingPeriodArchiveService
                .nextPeriodStart(CostResetPeriod.MONTHLY, start);

        assertThat(next).isEqualTo(YearMonth.of(2026, 6).atDay(1)
                .atStartOfDay(ZoneOffset.UTC).toInstant());
    }

    @Test
    void getAllHistoryNewestFirst_returnsCombinedListSortedByPeriodStart() {
        Instant april = YearMonth.of(2026, 4).atDay(1)
                .atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant may = YearMonth.of(2026, 5).atDay(1)
                .atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant dailyOld = LocalDate.of(2026, 5, 20)
                .atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant dailyNew = LocalDate.of(2026, 5, 22)
                .atStartOfDay(ZoneOffset.UTC).toInstant();
        SpendingPeriodArchive aprilArchive = new SpendingPeriodArchive(
                CostResetPeriod.MONTHLY, april, may,
                new BigDecimal("12.00"), 7L, null);
        SpendingPeriodArchive dailyOldArchive = new SpendingPeriodArchive(
                CostResetPeriod.DAILY, dailyOld, dailyOld.plusSeconds(86400),
                new BigDecimal("3.00"), 2L, null);
        SpendingPeriodArchive dailyNewArchive = new SpendingPeriodArchive(
                CostResetPeriod.DAILY, dailyNew, dailyNew.plusSeconds(86400),
                new BigDecimal("5.00"), 4L, null);
        // Return in unsorted order; the service should still produce newest first.
        when(archiveRepository.findAllByOrderByPeriodStartDesc()).thenReturn(
                List.of(dailyOldArchive, dailyNewArchive, aprilArchive));

        List<SpendingPeriodArchiveDto> all = service.getAllHistoryNewestFirst();

        assertThat(all).hasSize(3);
        assertThat(all.get(0).getPeriodStart()).isEqualTo(dailyNew);
        assertThat(all.get(1).getPeriodStart()).isEqualTo(dailyOld);
        assertThat(all.get(2).getPeriodStart()).isEqualTo(april);
    }

    private static ExpertReviewCost reviewAt(Instant at) {
        ExpertReviewCost cost = new ExpertReviewCost();
        cost.setCreatedAt(at);
        cost.setSuggestionId(1L);
        cost.setExpertName("Tester");
        cost.setCostUsd(new BigDecimal("0.01"));
        return cost;
    }
}
