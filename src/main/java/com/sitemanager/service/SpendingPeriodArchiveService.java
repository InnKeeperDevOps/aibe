package com.sitemanager.service;

import com.sitemanager.dto.SpendingPeriodArchiveDto;
import com.sitemanager.model.ExpertReviewCost;
import com.sitemanager.model.SiteSettings;
import com.sitemanager.model.SpendingPeriodArchive;
import com.sitemanager.model.enums.CostResetPeriod;
import com.sitemanager.repository.ExpertReviewCostRepository;
import com.sitemanager.repository.SpendingPeriodArchiveRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Captures the total spend of each completed daily and monthly window so
 * the prior period stays visible in dashboards and history even after the
 * live windowed query for the new window resets to zero. The new window
 * is never blocked from starting fresh; archival is purely additive — it
 * does not change what the budget check sees, only what the history view
 * shows.
 *
 * <p>The service is idempotent and safe to call as often as needed: a
 * uniqueness constraint on {@code (periodType, periodStart)} guarantees a
 * given window is archived at most once even under concurrent invocations.
 * The check runs:
 *
 * <ul>
 *   <li>once an hour from a scheduled task, so periods close on their own
 *       even when the dashboard is never opened;</li>
 *   <li>on every dashboard fetch, so admins always see the most recent
 *       completed window in history without waiting for the next tick.</li>
 * </ul>
 *
 * <p>If many windows have completed since the last archival run (after a
 * long outage, or because no costs were recorded for weeks), each missing
 * window is archived in chronological order. A per-invocation walk cap
 * keeps the worst-case work bounded.
 */
@Service
public class SpendingPeriodArchiveService {

    private static final Logger log = LoggerFactory.getLogger(SpendingPeriodArchiveService.class);

    /**
     * Hard cap on how many missing periods are archived in one invocation.
     * 400 daily periods is over a year of catch-up; 400 monthly periods is
     * 33 years. In practice the loop exits after one or two iterations.
     */
    static final int MAX_PERIODS_ARCHIVED_PER_INVOCATION = 400;

    /** Maximum history rows returned per period type, matching repo cap. */
    static final int HISTORY_ROWS_PER_PERIOD = 90;

    private final ExpertReviewCostRepository costRepository;
    private final SpendingPeriodArchiveRepository archiveRepository;
    private final SiteSettingsService settingsService;
    private final Clock clock;

    public SpendingPeriodArchiveService(ExpertReviewCostRepository costRepository,
                                        SpendingPeriodArchiveRepository archiveRepository,
                                        SiteSettingsService settingsService) {
        this(costRepository, archiveRepository, settingsService, Clock.systemUTC());
    }

    /** Test-friendly constructor. */
    SpendingPeriodArchiveService(ExpertReviewCostRepository costRepository,
                                 SpendingPeriodArchiveRepository archiveRepository,
                                 SiteSettingsService settingsService,
                                 Clock clock) {
        this.costRepository = costRepository;
        this.archiveRepository = archiveRepository;
        this.settingsService = settingsService;
        this.clock = clock;
    }

    /**
     * Walk forward through every completed daily / monthly window that has
     * not yet been archived and write a row for it. Best-effort: any
     * failure is logged and swallowed so callers (the scheduled tick, the
     * dashboard fetch) are never disrupted.
     *
     * @return the total number of archive rows written across both period
     *         types in this invocation
     */
    public int archiveIfPeriodRolledOver() {
        int total = 0;
        try {
            total += archivePeriodTypeIfNeeded(CostResetPeriod.DAILY);
        } catch (Exception e) {
            log.warn("Daily spending archive run failed: {}", e.getMessage());
        }
        try {
            total += archivePeriodTypeIfNeeded(CostResetPeriod.MONTHLY);
        } catch (Exception e) {
            log.warn("Monthly spending archive run failed: {}", e.getMessage());
        }
        return total;
    }

    /**
     * Scheduled hourly tick. Runs the archival walk even when no admin has
     * opened the dashboard, so historical rows appear on time.
     */
    @Scheduled(fixedDelayString = "${spending.archive.fixed-delay-ms:3600000}",
            initialDelayString = "${spending.archive.initial-delay-ms:60000}")
    public void scheduledArchive() {
        int written = archiveIfPeriodRolledOver();
        if (written > 0) {
            log.info("Scheduled spending archive run wrote {} archive row(s).", written);
        }
    }

    /**
     * Recent history entries for the daily window, newest first. Used by
     * the dashboard to render a "previous days" table; the new (live)
     * window is shown separately and is never included here.
     */
    public List<SpendingPeriodArchiveDto> getDailyHistory() {
        return loadHistory(CostResetPeriod.DAILY);
    }

    /** Recent monthly archives, newest first. */
    public List<SpendingPeriodArchiveDto> getMonthlyHistory() {
        return loadHistory(CostResetPeriod.MONTHLY);
    }

    private List<SpendingPeriodArchiveDto> loadHistory(CostResetPeriod periodType) {
        List<SpendingPeriodArchive> rows = archiveRepository
                .findTop90ByPeriodTypeOrderByPeriodStartDesc(periodType);
        if (rows == null || rows.isEmpty()) return List.of();
        List<SpendingPeriodArchiveDto> result = new ArrayList<>(rows.size());
        for (SpendingPeriodArchive row : rows) {
            result.add(SpendingPeriodArchiveDto.fromEntity(row));
        }
        return result;
    }

    private int archivePeriodTypeIfNeeded(CostResetPeriod periodType) {
        if (periodType != CostResetPeriod.DAILY && periodType != CostResetPeriod.MONTHLY) {
            return 0;
        }
        Instant now = Instant.now(clock);
        Instant currentPeriodStart = periodStartContaining(periodType, now);

        Instant cursor = findStartingCursor(periodType);
        if (cursor == null || !cursor.isBefore(currentPeriodStart)) {
            return 0;
        }

        BigDecimal currentLimit = currentGlobalLimit();
        int written = 0;
        int safetyCounter = 0;

        while (cursor.isBefore(currentPeriodStart)
                && safetyCounter < MAX_PERIODS_ARCHIVED_PER_INVOCATION) {
            Instant periodEnd = nextPeriodStart(periodType, cursor);
            if (writeArchiveIfMissing(periodType, cursor, periodEnd, currentLimit)) {
                written++;
            }
            cursor = periodEnd;
            safetyCounter++;
        }
        return written;
    }

    /**
     * Where to begin the archival walk for this period type — the period
     * directly after the most recently archived window, or, if no archive
     * exists yet, the window containing the oldest recorded cost. Returns
     * {@code null} when there is nothing to archive yet (no archives and
     * no costs).
     */
    private Instant findStartingCursor(CostResetPeriod periodType) {
        Optional<SpendingPeriodArchive> latest = archiveRepository
                .findFirstByPeriodTypeOrderByPeriodStartDesc(periodType);
        if (latest.isPresent()) {
            return nextPeriodStart(periodType, latest.get().getPeriodStart());
        }
        Optional<ExpertReviewCost> earliest = costRepository.findFirstByOrderByCreatedAtAsc();
        if (earliest.isEmpty() || earliest.get().getCreatedAt() == null) {
            return null;
        }
        return periodStartContaining(periodType, earliest.get().getCreatedAt());
    }

    private boolean writeArchiveIfMissing(CostResetPeriod periodType, Instant periodStart,
                                          Instant periodEnd, BigDecimal currentLimit) {
        if (archiveRepository.existsByPeriodTypeAndPeriodStart(periodType, periodStart)) {
            return false;
        }
        BigDecimal totalRaw = costRepository.sumCostBetween(periodStart, periodEnd);
        BigDecimal total = totalRaw != null ? totalRaw : BigDecimal.ZERO;
        long count = costRepository.countBetween(periodStart, periodEnd);
        SpendingPeriodArchive archive = new SpendingPeriodArchive(
                periodType, periodStart, periodEnd, total, count, currentLimit);
        archive.setArchivedAt(Instant.now(clock));
        try {
            archiveRepository.save(archive);
            log.info("Archived completed {} window starting {} — {} review(s), total {}",
                    periodType, periodStart, count, total.toPlainString());
            return true;
        } catch (DataIntegrityViolationException duplicate) {
            // A concurrent invocation got there first — silently skip.
            log.debug("Duplicate archive for {} starting {} — already written by a concurrent run",
                    periodType, periodStart);
            return false;
        }
    }

    private BigDecimal currentGlobalLimit() {
        try {
            SiteSettings settings = settingsService.getSettings();
            return settings != null ? settings.getMaxTotalCostUsd() : null;
        } catch (Exception e) {
            log.debug("Could not read global limit when archiving: {}", e.getMessage());
            return null;
        }
    }

    /** Start (inclusive) of the period that contains {@code at}, in UTC. */
    static Instant periodStartContaining(CostResetPeriod periodType, Instant at) {
        if (periodType == CostResetPeriod.DAILY) {
            return at.atZone(ZoneOffset.UTC).toLocalDate()
                    .atStartOfDay(ZoneOffset.UTC).toInstant();
        }
        if (periodType == CostResetPeriod.MONTHLY) {
            LocalDate date = at.atZone(ZoneOffset.UTC).toLocalDate();
            return YearMonth.from(date).atDay(1)
                    .atStartOfDay(ZoneOffset.UTC).toInstant();
        }
        return Instant.EPOCH;
    }

    /** Start (inclusive) of the period immediately following the one beginning at {@code currentStart}. */
    static Instant nextPeriodStart(CostResetPeriod periodType, Instant currentStart) {
        if (periodType == CostResetPeriod.DAILY) {
            return currentStart.atZone(ZoneOffset.UTC).toLocalDate()
                    .plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        }
        if (periodType == CostResetPeriod.MONTHLY) {
            LocalDate date = currentStart.atZone(ZoneOffset.UTC).toLocalDate();
            return YearMonth.from(date).plusMonths(1).atDay(1)
                    .atStartOfDay(ZoneOffset.UTC).toInstant();
        }
        return currentStart;
    }

    /** All recorded history rows across both period types, newest first. Mainly for tests/admin tooling. */
    public List<SpendingPeriodArchiveDto> getAllHistoryNewestFirst() {
        List<SpendingPeriodArchive> rows = archiveRepository.findAllByOrderByPeriodStartDesc();
        if (rows == null || rows.isEmpty()) return List.of();
        List<SpendingPeriodArchiveDto> result = new ArrayList<>(rows.size());
        for (SpendingPeriodArchive row : rows) {
            result.add(SpendingPeriodArchiveDto.fromEntity(row));
        }
        result.sort(Comparator
                .comparing(SpendingPeriodArchiveDto::getPeriodStart,
                        Comparator.nullsLast(Comparator.naturalOrder()))
                .reversed());
        return result;
    }
}
