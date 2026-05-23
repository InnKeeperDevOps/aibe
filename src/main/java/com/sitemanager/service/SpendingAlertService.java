package com.sitemanager.service;

import com.sitemanager.model.SiteSettings;
import com.sitemanager.model.SpendingAlertState;
import com.sitemanager.model.Suggestion;
import com.sitemanager.model.User;
import com.sitemanager.model.enums.CostResetPeriod;
import com.sitemanager.model.enums.UserRole;
import com.sitemanager.repository.ExpertReviewCostRepository;
import com.sitemanager.repository.SpendingAlertStateRepository;
import com.sitemanager.repository.SuggestionRepository;
import com.sitemanager.repository.UserRepository;
import com.sitemanager.websocket.UserNotificationWebSocketHandler;
import org.springframework.dao.DataIntegrityViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Watches spend totals after every recorded review and proactively warns
 * admins before a cap is reached, so they can raise limits in time rather
 * than discovering a block after work has already been refused.
 *
 * <p>The service compares the running spend to two kinds of trigger:
 *
 * <ul>
 *   <li><b>Warning thresholds</b> — percent values from {@link
 *       SiteSettings#getSpendingAlertThresholds()} (default 75 and 90).
 *       The first time spend within the relevant budget window crosses a
 *       threshold, one alert is sent and recorded so the same threshold
 *       does not fire again until the window resets.</li>
 *   <li><b>Cap reached</b> — when spend meets or exceeds the cap. Sent
 *       once per window, separately from any warning thresholds.</li>
 * </ul>
 *
 * <p>Alerts go to two surfaces:
 *
 * <ul>
 *   <li>Slack — via the existing webhook (if configured), so an admin's
 *       chat channel sees the warning even when nobody is logged in.</li>
 *   <li>Live notifications — pushed to admins currently connected over
 *       the user-notification WebSocket.</li>
 * </ul>
 *
 * <p>Recipients are derived server-side from the configured admin role
 * list and the optional server-side recipient list on
 * {@link SiteSettings#getSpendingAlertRecipients()}. The request that
 * recorded the cost never controls who receives the alert, so a hostile
 * payload cannot redirect alerts.
 *
 * <p>All suggestion content surfaced in alerts is sanitized through
 * {@link SpendingDashboardService#sanitize(String)} before being placed in
 * any outbound message, matching how the dashboard handles user-supplied
 * titles.
 */
@Service
public class SpendingAlertService {

    private static final Logger log = LoggerFactory.getLogger(SpendingAlertService.class);

    /** Marker used in place of a suggestion id for global-scope alerts. */
    static final String GLOBAL_SCOPE_KEY = "GLOBAL";

    /** Window key used for per-suggestion alerts (these never reset). */
    static final String LIFETIME_WINDOW = "LIFETIME";

    /** Percent value that represents the cap itself. */
    static final int CAP_REACHED_PERCENT = 100;

    /** Default warning thresholds applied when settings hold a blank value. */
    static final List<Integer> DEFAULT_THRESHOLDS = List.of(75, 90);

    private static final DateTimeFormatter DAILY_KEY_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter MONTHLY_KEY_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM");

    private final SiteSettingsService settingsService;
    private final ExpertReviewCostRepository costRepository;
    private final SpendingAlertStateRepository alertStateRepository;
    private final SuggestionRepository suggestionRepository;
    private final UserRepository userRepository;
    private final SlackNotificationService slackNotificationService;
    private final UserNotificationWebSocketHandler userNotificationHandler;

    public SpendingAlertService(SiteSettingsService settingsService,
                                ExpertReviewCostRepository costRepository,
                                SpendingAlertStateRepository alertStateRepository,
                                SuggestionRepository suggestionRepository,
                                UserRepository userRepository,
                                SlackNotificationService slackNotificationService,
                                UserNotificationWebSocketHandler userNotificationHandler) {
        this.settingsService = settingsService;
        this.costRepository = costRepository;
        this.alertStateRepository = alertStateRepository;
        this.suggestionRepository = suggestionRepository;
        this.userRepository = userRepository;
        this.slackNotificationService = slackNotificationService;
        this.userNotificationHandler = userNotificationHandler;
    }

    /**
     * Inspect the current spend for the given suggestion (when set) and
     * for the global budget window. Fires any threshold or cap-reached
     * alerts that have just been crossed for the first time in their
     * window. Safe to call after every recorded review — alerts that have
     * already been delivered for the current window are silently skipped.
     *
     * <p>Best-effort: any failure is logged and swallowed so the calling
     * cost-recording pipeline is never disrupted by alert delivery.
     */
    public void evaluateAfterCostRecorded(Long suggestionId) {
        SiteSettings settings;
        try {
            settings = settingsService.getSettings();
        } catch (Exception e) {
            log.warn("Could not load settings for spending alert evaluation: {}", e.getMessage());
            return;
        }
        if (!settings.isSpendingAlertsEnabled()) return;

        List<Integer> thresholds = parseThresholds(settings.getSpendingAlertThresholds());

        try {
            evaluatePerSuggestion(suggestionId, settings, thresholds);
        } catch (Exception e) {
            log.warn("Per-suggestion spending alert evaluation failed for suggestion {}: {}",
                    suggestionId, e.getMessage());
        }
        try {
            evaluateGlobal(settings, thresholds);
        } catch (Exception e) {
            log.warn("Global spending alert evaluation failed: {}", e.getMessage());
        }
    }

    private void evaluatePerSuggestion(Long suggestionId, SiteSettings settings,
                                       List<Integer> thresholds) {
        if (suggestionId == null) return;
        BigDecimal limit = settings.getMaxCostPerSuggestionUsd();
        if (limit == null || limit.signum() <= 0) return;

        BigDecimal spend = costRepository.sumCostBySuggestionId(suggestionId);
        if (spend == null) spend = BigDecimal.ZERO;

        for (int triggered : thresholdsCrossed(spend, limit, thresholds)) {
            tryFire(SpendingAlertState.Scope.PER_SUGGESTION,
                    String.valueOf(suggestionId), LIFETIME_WINDOW,
                    triggered, spend, limit, suggestionId, settings);
        }
    }

    private void evaluateGlobal(SiteSettings settings, List<Integer> thresholds) {
        BigDecimal limit = settings.getMaxTotalCostUsd();
        if (limit == null || limit.signum() <= 0) return;

        CostResetPeriod period = settings.getGlobalCostResetPeriod() != null
                ? settings.getGlobalCostResetPeriod() : CostResetPeriod.NEVER;
        BigDecimal spend = computeGlobalSpend(period);
        String windowKey = globalWindowKey(period);

        for (int triggered : thresholdsCrossed(spend, limit, thresholds)) {
            tryFire(SpendingAlertState.Scope.GLOBAL, GLOBAL_SCOPE_KEY,
                    windowKey, triggered, spend, limit, null, settings);
        }
    }

    /**
     * Return the set of triggers (warning thresholds plus 100 for cap
     * reached) the current spend has reached, ordered low to high. A
     * trigger {@code t} is reached when {@code spend / limit * 100 >= t}.
     */
    static List<Integer> thresholdsCrossed(BigDecimal spend, BigDecimal limit,
                                           List<Integer> warningThresholds) {
        if (spend == null || limit == null || limit.signum() <= 0) return List.of();
        // Compute percent at high precision to avoid rounding a 74.999% up to
        // 75% and firing an alert before the threshold is truly crossed.
        BigDecimal pct = spend.multiply(BigDecimal.valueOf(100))
                .divide(limit, 6, RoundingMode.DOWN);
        Set<Integer> sorted = new TreeSet<>();
        if (warningThresholds != null) {
            for (Integer t : warningThresholds) {
                if (t == null) continue;
                if (t <= 0 || t >= 100) continue;
                if (pct.compareTo(BigDecimal.valueOf(t)) >= 0) {
                    sorted.add(t);
                }
            }
        }
        if (pct.compareTo(BigDecimal.valueOf(100)) >= 0) {
            sorted.add(CAP_REACHED_PERCENT);
        }
        return new ArrayList<>(sorted);
    }

    /**
     * Parse the comma-separated threshold list, rejecting empty / invalid
     * values and falling back to {@link #DEFAULT_THRESHOLDS} when nothing
     * usable is present. Values are de-duplicated and sorted ascending so
     * the dispatch order is deterministic.
     */
    static List<Integer> parseThresholds(String csv) {
        if (csv == null || csv.isBlank()) return DEFAULT_THRESHOLDS;
        Set<Integer> values = new TreeSet<>();
        for (String token : csv.split("[,\\s]+")) {
            if (token.isBlank()) continue;
            try {
                int v = Integer.parseInt(token.trim());
                if (v > 0 && v < 100) values.add(v);
            } catch (NumberFormatException ignored) {
                // skip malformed token rather than failing the whole alert
            }
        }
        if (values.isEmpty()) return DEFAULT_THRESHOLDS;
        return new ArrayList<>(values);
    }

    private void tryFire(SpendingAlertState.Scope scope, String scopeKey,
                         String windowKey, int thresholdPercent,
                         BigDecimal spend, BigDecimal limit, Long suggestionId,
                         SiteSettings settings) {
        if (alertStateRepository.findByScopeAndScopeKeyAndWindowKeyAndThresholdPercent(
                scope, scopeKey, windowKey, thresholdPercent).isPresent()) {
            return;
        }
        try {
            alertStateRepository.save(new SpendingAlertState(
                    scope, scopeKey, windowKey, thresholdPercent, spend, limit));
        } catch (DataIntegrityViolationException duplicateAlert) {
            // Another concurrent recorder won the race — the alert was
            // already sent, so we silently drop this attempt instead of
            // double-firing.
            log.debug("Duplicate spending alert {} {}/{} @ {}% — already recorded",
                    scope, scopeKey, windowKey, thresholdPercent);
            return;
        }
        dispatch(scope, thresholdPercent, spend, limit, suggestionId, settings);
    }

    private void dispatch(SpendingAlertState.Scope scope, int thresholdPercent,
                          BigDecimal spend, BigDecimal limit, Long suggestionId,
                          SiteSettings settings) {
        String title = buildHumanTitle(scope, thresholdPercent);
        String safeSuggestionTitle = lookupSanitizedTitle(suggestionId);
        String body = buildHumanBody(scope, thresholdPercent, spend, limit,
                suggestionId, safeSuggestionTitle, settings);

        try {
            slackNotificationService.sendSpendingAlert(title, body).join();
        } catch (Exception e) {
            log.warn("Failed to send Slack spending alert: {}", e.getMessage());
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "spending_alert");
        payload.put("scope", scope.name());
        payload.put("thresholdPercent", thresholdPercent);
        payload.put("capReached", thresholdPercent >= CAP_REACHED_PERCENT);
        payload.put("currentSpendUsd", spend);
        payload.put("limitUsd", limit);
        payload.put("title", title);
        payload.put("body", body);
        if (suggestionId != null) {
            payload.put("suggestionId", suggestionId);
            payload.put("suggestionTitle", safeSuggestionTitle);
        }
        for (String recipient : resolveRecipients(settings)) {
            try {
                userNotificationHandler.sendNotificationToUser(recipient, payload);
            } catch (Exception e) {
                log.warn("Failed to push spending alert to {}: {}", recipient, e.getMessage());
            }
        }
    }

    private String lookupSanitizedTitle(Long suggestionId) {
        if (suggestionId == null) return null;
        try {
            Suggestion s = suggestionRepository.findById(suggestionId).orElse(null);
            if (s == null) return null;
            return SpendingDashboardService.sanitize(s.getTitle());
        } catch (Exception e) {
            log.warn("Failed to load suggestion {} for alert sanitization: {}",
                    suggestionId, e.getMessage());
            return null;
        }
    }

    /**
     * Build the human-readable headline an admin sees in chat or in the
     * in-app notification list. Keeps the user-facing wording free of
     * technical detail per the project's communication rules.
     */
    static String buildHumanTitle(SpendingAlertState.Scope scope, int thresholdPercent) {
        boolean capReached = thresholdPercent >= CAP_REACHED_PERCENT;
        if (scope == SpendingAlertState.Scope.PER_SUGGESTION) {
            return capReached
                    ? "Suggestion spending limit reached"
                    : "Heads up: suggestion is at " + thresholdPercent + "% of its spending limit";
        }
        return capReached
                ? "Overall spending limit reached"
                : "Heads up: overall spending is at " + thresholdPercent + "% of the limit";
    }

    static String buildHumanBody(SpendingAlertState.Scope scope, int thresholdPercent,
                                 BigDecimal spend, BigDecimal limit, Long suggestionId,
                                 String sanitizedSuggestionTitle, SiteSettings settings) {
        StringBuilder sb = new StringBuilder();
        boolean capReached = thresholdPercent >= CAP_REACHED_PERCENT;
        if (scope == SpendingAlertState.Scope.PER_SUGGESTION) {
            sb.append("Suggestion #").append(suggestionId);
            if (sanitizedSuggestionTitle != null && !sanitizedSuggestionTitle.isBlank()) {
                sb.append(" (").append(sanitizedSuggestionTitle).append(")");
            }
            sb.append(capReached
                    ? " has reached its spending limit and new reviews will be blocked"
                    : " has used " + thresholdPercent + "% of its spending limit");
            sb.append(". So far it has used ").append(formatUsd(spend))
                    .append(" of its ").append(formatUsd(limit)).append(" limit.");
            if (capReached) {
                sb.append(" Raise the limit to allow more reviews on this suggestion.");
            } else {
                sb.append(" Consider raising the limit before more work is blocked.");
            }
        } else {
            CostResetPeriod period = settings.getGlobalCostResetPeriod() != null
                    ? settings.getGlobalCostResetPeriod() : CostResetPeriod.NEVER;
            if (capReached) {
                sb.append("The overall spending limit has been reached and new reviews will be blocked. ");
            } else {
                sb.append("Overall spending has reached ").append(thresholdPercent)
                        .append("% of the limit. ");
            }
            if (period == CostResetPeriod.DAILY) {
                sb.append("So far today the platform has used ");
            } else if (period == CostResetPeriod.MONTHLY) {
                sb.append("So far this month the platform has used ");
            } else {
                sb.append("So far the platform has used ");
            }
            sb.append(formatUsd(spend)).append(" of the ")
                    .append(formatUsd(limit)).append(" limit.");
            if (capReached) {
                if (period == CostResetPeriod.DAILY) {
                    sb.append(" New reviews will resume after midnight UTC, or raise the limit to resume sooner.");
                } else if (period == CostResetPeriod.MONTHLY) {
                    sb.append(" New reviews will resume at the start of next month (UTC), or raise the limit to resume sooner.");
                } else {
                    sb.append(" Raise the limit to allow new reviews to resume.");
                }
            } else {
                sb.append(" Consider raising the limit before more work is blocked.");
            }
        }
        return sb.toString();
    }

    private Set<String> resolveRecipients(SiteSettings settings) {
        Set<String> usernames = new LinkedHashSet<>();
        try {
            for (User u : userRepository.findAll()) {
                if (u == null || u.getUsername() == null) continue;
                if (u.getRole() == UserRole.ROOT_ADMIN || u.getRole() == UserRole.ADMIN) {
                    usernames.add(u.getUsername());
                }
            }
        } catch (Exception e) {
            log.warn("Could not load admin recipients: {}", e.getMessage());
        }
        String configured = settings.getSpendingAlertRecipients();
        if (configured != null && !configured.isBlank()) {
            for (String token : configured.split("[,\\n\\r]+")) {
                String trimmed = token.trim();
                if (!trimmed.isEmpty()) usernames.add(trimmed);
            }
        }
        return usernames;
    }

    private BigDecimal computeGlobalSpend(CostResetPeriod period) {
        BigDecimal total;
        if (period == CostResetPeriod.NEVER) {
            total = costRepository.sumCostGlobal();
        } else {
            total = costRepository.sumCostSince(windowStart(period));
        }
        return total != null ? total : BigDecimal.ZERO;
    }

    private static Instant windowStart(CostResetPeriod period) {
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

    /**
     * Build a stable key for the current global budget window so the same
     * threshold can fire again only after the window rolls over.
     */
    static String globalWindowKey(CostResetPeriod period) {
        if (period == CostResetPeriod.DAILY) {
            return "DAILY:" + LocalDate.now(ZoneOffset.UTC).format(DAILY_KEY_FMT);
        }
        if (period == CostResetPeriod.MONTHLY) {
            return "MONTHLY:" + YearMonth.now(ZoneOffset.UTC).format(MONTHLY_KEY_FMT);
        }
        return "NEVER";
    }

    private static String formatUsd(BigDecimal value) {
        BigDecimal rounded = (value != null ? value : BigDecimal.ZERO)
                .setScale(2, RoundingMode.HALF_UP);
        return "$" + rounded.toPlainString();
    }

    /**
     * Drop recorded per-suggestion alert rows so the configured thresholds
     * become eligible to fire again under the new limit. Called from the
     * settings update flow when the per-suggestion cap changes.
     */
    public void resetPerSuggestionAlerts(Long suggestionId) {
        if (suggestionId == null) return;
        try {
            alertStateRepository.deletePerSuggestionAlerts(String.valueOf(suggestionId));
        } catch (Exception e) {
            log.warn("Could not reset per-suggestion alerts for {}: {}", suggestionId, e.getMessage());
        }
    }

    /**
     * Drop all recorded global alert rows so the configured thresholds
     * become eligible to fire again under the new global limit or reset
     * period.
     */
    public void resetGlobalAlerts() {
        try {
            alertStateRepository.deleteAllGlobalAlerts();
        } catch (Exception e) {
            log.warn("Could not reset global spending alerts: {}", e.getMessage());
        }
    }

    // Exposed for tests
    static String globalScopeKey() { return GLOBAL_SCOPE_KEY; }
    static String lifetimeWindow() { return LIFETIME_WINDOW; }

    /** Returns a defensive copy of the default thresholds for callers that need them. */
    public static List<Integer> defaultThresholds() {
        return new ArrayList<>(DEFAULT_THRESHOLDS);
    }

    /** Visible for advanced configuration validation. */
    static List<Integer> parseConfiguredThresholds(String csv) {
        return parseThresholds(csv);
    }

    /** Returns the warning thresholds applied to a settings record. */
    public List<Integer> getWarningThresholds(SiteSettings settings) {
        return new ArrayList<>(parseThresholds(settings.getSpendingAlertThresholds()));
    }
}
