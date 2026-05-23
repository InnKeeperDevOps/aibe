package com.sitemanager.service;

import com.sitemanager.dto.GlobalCapStatusDto;
import com.sitemanager.dto.RecentReviewDto;
import com.sitemanager.dto.SpendingDashboardDto;
import com.sitemanager.dto.SpendingPeriodArchiveDto;
import com.sitemanager.dto.SpendingTrendPointDto;
import com.sitemanager.dto.TopSpendingSuggestionDto;
import com.sitemanager.model.ExpertReviewCost;
import com.sitemanager.model.SiteSettings;
import com.sitemanager.model.Suggestion;
import com.sitemanager.model.enums.CostResetPeriod;
import com.sitemanager.repository.ExpertReviewCostRepository;
import com.sitemanager.repository.SuggestionRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Assembles the read-only data the admin spending dashboard renders: the
 * status of the global cap inside the active reset window, the suggestions
 * burning the most budget, a per-day trend of spend over a recent window,
 * and the most recent recorded reviews.
 *
 * <p>Every value comes straight from the cost ledger and the settings
 * record, with no caching, so each dashboard fetch reflects the current
 * state of the platform. The window and result sizes are bounded so the
 * payload stays small even when the ledger has grown large.
 *
 * <p>Suggestion titles surfaced through this service are sanitized before
 * being placed in the response — every user-supplied string is HTML-escaped
 * and length-capped so the dashboard and any email built from the same
 * payload can render the title inline without becoming an injection
 * surface. Sanitization happens here, in the producer, rather than in the
 * UI, so all consumers benefit automatically.
 */
@Service
public class SpendingDashboardService {

    /** Default number of days included in the per-day trend series. */
    public static final int DEFAULT_TREND_WINDOW_DAYS = 30;

    /** Hard cap on how many days the trend may span, to keep the payload bounded. */
    public static final int MAX_TREND_WINDOW_DAYS = 180;

    /** Default top-spending suggestions to include in the dashboard. */
    public static final int DEFAULT_TOP_SUGGESTIONS = 10;

    /** Hard cap on the top-suggestions list size. */
    public static final int MAX_TOP_SUGGESTIONS = 50;

    /** Maximum displayed length of any user-supplied suggestion title. */
    static final int MAX_TITLE_LENGTH = 120;

    private final ExpertReviewCostRepository costRepository;
    private final SuggestionRepository suggestionRepository;
    private final SiteSettingsService settingsService;
    private final SpendingPeriodArchiveService periodArchiveService;

    public SpendingDashboardService(ExpertReviewCostRepository costRepository,
                                    SuggestionRepository suggestionRepository,
                                    SiteSettingsService settingsService,
                                    SpendingPeriodArchiveService periodArchiveService) {
        this.costRepository = costRepository;
        this.suggestionRepository = suggestionRepository;
        this.settingsService = settingsService;
        this.periodArchiveService = periodArchiveService;
    }

    /**
     * Build the dashboard payload using the default window sizes.
     */
    public SpendingDashboardDto buildDashboard() {
        return buildDashboard(DEFAULT_TREND_WINDOW_DAYS, DEFAULT_TOP_SUGGESTIONS);
    }

    /**
     * Build the dashboard payload using caller-specified window sizes.
     * Inputs are clamped to the configured minimums and maximums so the
     * service cannot be coerced into producing an unbounded response by
     * a careless or hostile caller.
     */
    public SpendingDashboardDto buildDashboard(int trendWindowDays, int topSuggestionsLimit) {
        int days = clamp(trendWindowDays, 1, MAX_TREND_WINDOW_DAYS, DEFAULT_TREND_WINDOW_DAYS);
        int topN = clamp(topSuggestionsLimit, 1, MAX_TOP_SUGGESTIONS, DEFAULT_TOP_SUGGESTIONS);

        // Make sure any windows that completed since the last fetch are
        // captured in history before the dashboard reads them, so the
        // "previous period" entries always show up promptly even when the
        // hourly background tick hasn't yet caught the latest rollover.
        if (periodArchiveService != null) {
            try {
                periodArchiveService.archiveIfPeriodRolledOver();
            } catch (Exception ignored) {
                // Archival is best-effort — the live numbers must still render
                // even if the archive walk fails.
            }
        }

        SiteSettings settings = settingsService.getSettings();
        GlobalCapStatusDto capStatus = buildGlobalCapStatus(settings);
        List<TopSpendingSuggestionDto> top = buildTopSuggestions(topN, settings);
        List<SpendingTrendPointDto> trend = buildTrend(days);
        List<RecentReviewDto> recent = buildRecentReviews();
        List<SpendingPeriodArchiveDto> dailyHistory = loadDailyHistory();
        List<SpendingPeriodArchiveDto> monthlyHistory = loadMonthlyHistory();

        return new SpendingDashboardDto(capStatus, top, trend, recent,
                dailyHistory, monthlyHistory, days, Instant.now());
    }

    private List<SpendingPeriodArchiveDto> loadDailyHistory() {
        if (periodArchiveService == null) return List.of();
        try {
            return periodArchiveService.getDailyHistory();
        } catch (Exception e) {
            return List.of();
        }
    }

    private List<SpendingPeriodArchiveDto> loadMonthlyHistory() {
        if (periodArchiveService == null) return List.of();
        try {
            return periodArchiveService.getMonthlyHistory();
        } catch (Exception e) {
            return List.of();
        }
    }

    // ----- global cap --------------------------------------------------------

    private GlobalCapStatusDto buildGlobalCapStatus(SiteSettings settings) {
        CostResetPeriod period = settings.getGlobalCostResetPeriod() != null
                ? settings.getGlobalCostResetPeriod()
                : CostResetPeriod.NEVER;
        Instant windowStart = windowStart(period);
        Instant nextReset = nextResetInstant(period);
        BigDecimal currentSpend = computeWindowSpend(period, windowStart);
        BigDecimal limit = settings.getMaxTotalCostUsd();
        if (limit == null || limit.signum() <= 0) {
            return GlobalCapStatusDto.unlimited(currentSpend, period, windowStart, nextReset);
        }
        return GlobalCapStatusDto.enforced(currentSpend, limit, period, windowStart, nextReset);
    }

    private BigDecimal computeWindowSpend(CostResetPeriod period, Instant windowStart) {
        BigDecimal total;
        if (period == CostResetPeriod.NEVER) {
            total = costRepository.sumCostGlobal();
        } else {
            total = costRepository.sumCostSince(windowStart);
        }
        return total != null ? total : BigDecimal.ZERO;
    }

    private Instant windowStart(CostResetPeriod period) {
        if (period == CostResetPeriod.DAILY) {
            return LocalDate.now(ZoneOffset.UTC)
                    .atStartOfDay(ZoneOffset.UTC)
                    .toInstant();
        }
        if (period == CostResetPeriod.MONTHLY) {
            return YearMonth.now(ZoneOffset.UTC)
                    .atDay(1)
                    .atStartOfDay(ZoneOffset.UTC)
                    .toInstant();
        }
        return Instant.EPOCH;
    }

    private Instant nextResetInstant(CostResetPeriod period) {
        if (period == CostResetPeriod.DAILY) {
            return LocalDate.now(ZoneOffset.UTC)
                    .plusDays(1)
                    .atStartOfDay(ZoneOffset.UTC)
                    .toInstant();
        }
        if (period == CostResetPeriod.MONTHLY) {
            return YearMonth.now(ZoneOffset.UTC)
                    .plusMonths(1)
                    .atDay(1)
                    .atStartOfDay(ZoneOffset.UTC)
                    .toInstant();
        }
        return null;
    }

    // ----- top suggestions ---------------------------------------------------

    private List<TopSpendingSuggestionDto> buildTopSuggestions(int limit, SiteSettings settings) {
        List<Object[]> rows = costRepository.findSuggestionSpendTotalsOrderedDesc();
        if (rows == null || rows.isEmpty()) return List.of();

        int take = Math.min(limit, rows.size());
        List<Object[]> top = rows.subList(0, take);

        // Load titles in one go to avoid an N+1 query against the suggestions table.
        Set<Long> ids = new HashSet<>();
        for (Object[] row : top) {
            Long suggestionId = toLong(row[0]);
            if (suggestionId != null) ids.add(suggestionId);
        }
        Map<Long, String> titles = loadTitles(ids);

        BigDecimal perSuggestionLimit = settings.getMaxCostPerSuggestionUsd();
        List<TopSpendingSuggestionDto> result = new ArrayList<>(top.size());
        for (Object[] row : top) {
            Long suggestionId = toLong(row[0]);
            BigDecimal total = (BigDecimal) row[1];
            long count = toLong(row[2]) != null ? toLong(row[2]) : 0L;
            String safeTitle = titles.getOrDefault(suggestionId, "(suggestion not found)");
            result.add(new TopSpendingSuggestionDto(
                    suggestionId, safeTitle, count, total, perSuggestionLimit));
        }
        return result;
    }

    private Map<Long, String> loadTitles(Set<Long> ids) {
        if (ids.isEmpty()) return Map.of();
        Map<Long, String> result = new HashMap<>();
        for (Suggestion s : suggestionRepository.findAllById(ids)) {
            result.put(s.getId(), sanitize(s.getTitle()));
        }
        return result;
    }

    // ----- per-day trend -----------------------------------------------------

    private List<SpendingTrendPointDto> buildTrend(int windowDays) {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        LocalDate windowStartDate = today.minusDays(windowDays - 1L);
        Instant windowStart = windowStartDate.atStartOfDay(ZoneOffset.UTC).toInstant();

        // Seed every day in the window with zeros so days without activity
        // still appear in the chart — gaps are misleading on a time series.
        Map<LocalDate, BigDecimal> costByDay = new LinkedHashMap<>();
        Map<LocalDate, Long> countByDay = new HashMap<>();
        for (int i = 0; i < windowDays; i++) {
            LocalDate day = windowStartDate.plusDays(i);
            costByDay.put(day, BigDecimal.ZERO);
            countByDay.put(day, 0L);
        }

        List<ExpertReviewCost> rows = costRepository
                .findByCreatedAtGreaterThanEqualOrderByCreatedAtAsc(windowStart);
        for (ExpertReviewCost row : rows) {
            LocalDate day = row.getCreatedAt().atZone(ZoneOffset.UTC).toLocalDate();
            // Defensive: ignore rows that somehow land outside the requested
            // window (clock skew between insert time and read time, etc.).
            if (!costByDay.containsKey(day)) continue;
            BigDecimal cost = row.getCostUsd() != null ? row.getCostUsd() : BigDecimal.ZERO;
            costByDay.merge(day, cost, BigDecimal::add);
            countByDay.merge(day, 1L, Long::sum);
        }

        List<SpendingTrendPointDto> result = new ArrayList<>(windowDays);
        for (Map.Entry<LocalDate, BigDecimal> e : costByDay.entrySet()) {
            result.add(new SpendingTrendPointDto(
                    e.getKey(), e.getValue(), countByDay.getOrDefault(e.getKey(), 0L)));
        }
        return result;
    }

    // ----- recent reviews ----------------------------------------------------

    private List<RecentReviewDto> buildRecentReviews() {
        List<ExpertReviewCost> rows = costRepository.findTop20ByOrderByCreatedAtDesc();
        if (rows == null || rows.isEmpty()) return List.of();

        Set<Long> ids = new HashSet<>();
        for (ExpertReviewCost row : rows) {
            if (row.getSuggestionId() != null) ids.add(row.getSuggestionId());
        }
        Map<Long, String> titles = loadTitles(ids);

        List<RecentReviewDto> result = new ArrayList<>(rows.size());
        for (ExpertReviewCost row : rows) {
            String safeTitle = titles.getOrDefault(row.getSuggestionId(), "(suggestion not found)");
            result.add(new RecentReviewDto(
                    row.getId(),
                    row.getSuggestionId(),
                    safeTitle,
                    sanitize(row.getExpertName()),
                    row.getTotalTokens(),
                    row.getCostUsd(),
                    row.getDurationMs(),
                    row.getCreatedAt()
            ));
        }
        return result;
    }

    // ----- helpers -----------------------------------------------------------

    /**
     * Make a user-supplied string safe to render inline in HTML / email:
     * HTML-escape the dangerous characters, collapse runs of whitespace,
     * and truncate so a single malicious title cannot blow up the
     * dashboard layout. The producer sanitizes once here so every consumer
     * (HTML page, email body) gets a value it can paste in directly.
     */
    static String sanitize(String value) {
        if (value == null) return "";
        String collapsed = value.replaceAll("\\s+", " ").trim();
        StringBuilder sb = new StringBuilder(collapsed.length());
        for (int i = 0; i < collapsed.length(); i++) {
            char c = collapsed.charAt(i);
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&#39;");
                default -> {
                    // Drop control characters (anything below the space char) that
                    // have no place in a title — tabs and newlines have already
                    // been collapsed to a single space above.
                    if (c >= 0x20) sb.append(c);
                }
            }
        }
        if (sb.length() > MAX_TITLE_LENGTH) {
            sb.setLength(MAX_TITLE_LENGTH);
            sb.append("…");
        }
        return sb.toString();
    }

    private static int clamp(int value, int min, int max, int fallback) {
        if (value <= 0) return fallback;
        if (value < min) return min;
        return Math.min(value, max);
    }

    private static Long toLong(Object value) {
        if (value == null) return null;
        if (value instanceof Long l) return l;
        if (value instanceof Number n) return n.longValue();
        return null;
    }
}
