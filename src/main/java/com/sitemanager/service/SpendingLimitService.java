package com.sitemanager.service;

import com.sitemanager.model.SiteSettings;
import com.sitemanager.model.enums.CostResetPeriod;
import com.sitemanager.repository.ExpertReviewCostRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;

/**
 * Decides whether a new expert review may be started given the current spend
 * relative to the configured per-suggestion and global caps. The check is
 * non-disruptive: a review whose CLI call is already running is never
 * interrupted, but a new review is refused when the relevant total has
 * already met or exceeded its cap.
 *
 * <p>Global spend is computed against the configured reset window
 * ({@link CostResetPeriod}): NEVER uses the lifetime running total, DAILY
 * uses only the current UTC day, and MONTHLY uses only the current UTC
 * calendar month.
 *
 * <p>When a review is refused, the {@link LimitCheck} returned carries both
 * a fully-formed plain-English reason suitable for posting straight into a
 * user-facing message and the structured pieces behind it (which limit, the
 * current spend, the cap, and when the cap will free up). That keeps the
 * "why was I blocked?" explanation honest and consistent across every
 * surface that displays it.
 */
@Service
public class SpendingLimitService {

    private static final Logger log = LoggerFactory.getLogger(SpendingLimitService.class);

    private final SiteSettingsService settingsService;
    private final ExpertReviewCostRepository costRepository;

    public SpendingLimitService(SiteSettingsService settingsService,
                                ExpertReviewCostRepository costRepository) {
        this.settingsService = settingsService;
        this.costRepository = costRepository;
    }

    /** Which cap was hit when a review was refused. */
    public enum LimitType {
        /** The per-suggestion spending limit applied to a single suggestion. */
        PER_SUGGESTION,
        /** The global spending limit applied across all suggestions. */
        GLOBAL
    }

    /**
     * Outcome of a budget check: either allowed, or refused with a
     * human-readable reason suitable for surfacing to users plus the
     * structured pieces that produced the reason (the cap, the running
     * spend, and — when applicable — when the cap will free up again).
     */
    public static final class LimitCheck {
        private final boolean allowed;
        private final String reason;
        private final LimitType limitType;
        private final BigDecimal currentSpend;
        private final BigDecimal limitAmount;
        private final CostResetPeriod resetPeriod;
        private final Instant resetsAt;

        private LimitCheck(boolean allowed, String reason, LimitType limitType,
                           BigDecimal currentSpend, BigDecimal limitAmount,
                           CostResetPeriod resetPeriod, Instant resetsAt) {
            this.allowed = allowed;
            this.reason = reason;
            this.limitType = limitType;
            this.currentSpend = currentSpend;
            this.limitAmount = limitAmount;
            this.resetPeriod = resetPeriod;
            this.resetsAt = resetsAt;
        }

        public boolean isAllowed() { return allowed; }

        public String getReason() { return reason; }

        public LimitType getLimitType() { return limitType; }

        public BigDecimal getCurrentSpend() { return currentSpend; }

        public BigDecimal getLimitAmount() { return limitAmount; }

        public CostResetPeriod getResetPeriod() { return resetPeriod; }

        public Instant getResetsAt() { return resetsAt; }

        public static LimitCheck allowed() {
            return new LimitCheck(true, null, null, null, null, null, null);
        }

        /**
         * Build a refused result with only a plain reason and no structured
         * detail. Kept for callers (mostly tests) that want to assert on the
         * message alone.
         */
        public static LimitCheck refused(String reason) {
            return new LimitCheck(false, reason, null, null, null, null, null);
        }

        static LimitCheck refusedPerSuggestion(String reason, BigDecimal currentSpend,
                                               BigDecimal limitAmount) {
            return new LimitCheck(false, reason, LimitType.PER_SUGGESTION,
                    currentSpend, limitAmount, null, null);
        }

        static LimitCheck refusedGlobal(String reason, BigDecimal currentSpend,
                                        BigDecimal limitAmount, CostResetPeriod resetPeriod,
                                        Instant resetsAt) {
            return new LimitCheck(false, reason, LimitType.GLOBAL,
                    currentSpend, limitAmount, resetPeriod, resetsAt);
        }
    }

    /**
     * Check whether a new review for {@code suggestionId} may be started.
     * Refuses when either the per-suggestion total or the windowed global
     * total has already met or exceeded its configured cap. Returns
     * {@link LimitCheck#allowed()} when no cap is configured or the cap is
     * non-positive (treated as "not enforced").
     *
     * <p>When refused, the reason carried by the result is friendly enough
     * to display directly to users: it names which cap was hit, quotes the
     * exact spend and the exact cap, and explains how (or when) the cap
     * will free up — so users never see a silent block.
     */
    public LimitCheck checkCanStartReview(Long suggestionId) {
        SiteSettings settings = settingsService.getSettings();

        BigDecimal perSuggestionLimit = settings.getMaxCostPerSuggestionUsd();
        if (suggestionId != null && perSuggestionLimit != null
                && perSuggestionLimit.signum() > 0) {
            BigDecimal spend = costRepository.sumCostBySuggestionId(suggestionId);
            if (spend == null) spend = BigDecimal.ZERO;
            if (spend.compareTo(perSuggestionLimit) >= 0) {
                String reason = buildPerSuggestionRefusalMessage(spend, perSuggestionLimit);
                log.info("Refusing new review for suggestion {} — per-suggestion spend ${} reached cap ${}",
                        suggestionId, spend.toPlainString(), perSuggestionLimit.toPlainString());
                return LimitCheck.refusedPerSuggestion(reason, spend, perSuggestionLimit);
            }
        }

        BigDecimal globalLimit = settings.getMaxTotalCostUsd();
        if (globalLimit != null && globalLimit.signum() > 0) {
            CostResetPeriod period = settings.getGlobalCostResetPeriod() != null
                    ? settings.getGlobalCostResetPeriod() : CostResetPeriod.NEVER;
            BigDecimal globalSpend = computeGlobalSpend(period);
            if (globalSpend.compareTo(globalLimit) >= 0) {
                Instant resetsAt = nextResetInstant(period);
                String reason = buildGlobalRefusalMessage(globalSpend, globalLimit, period);
                log.info("Refusing new review for suggestion {} — global spend ${} reached cap ${} (window: {})",
                        suggestionId, globalSpend.toPlainString(),
                        globalLimit.toPlainString(), period);
                return LimitCheck.refusedGlobal(reason, globalSpend, globalLimit, period, resetsAt);
            }
        }

        return LimitCheck.allowed();
    }

    /**
     * Total spend across all suggestions within the active reset window.
     * Exposed so dashboards and admin tooling can show the same number that
     * the budget check uses.
     */
    public BigDecimal getGlobalSpendInWindow() {
        SiteSettings settings = settingsService.getSettings();
        CostResetPeriod period = settings.getGlobalCostResetPeriod() != null
                ? settings.getGlobalCostResetPeriod() : CostResetPeriod.NEVER;
        return computeGlobalSpend(period);
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

    /**
     * When the active window will next reset, or {@code null} for NEVER —
     * useful both for the refusal message and for any caller that wants to
     * show users a countdown.
     */
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

    private String buildPerSuggestionRefusalMessage(BigDecimal currentSpend,
                                                    BigDecimal limitAmount) {
        return "A new review cannot be started right now because this suggestion has reached"
                + " its spending limit. So far it has used " + formatUsd(currentSpend)
                + " of its " + formatUsd(limitAmount) + " limit."
                + " An administrator can raise the limit if more reviews are needed.";
    }

    private String buildGlobalRefusalMessage(BigDecimal currentSpend, BigDecimal limitAmount,
                                             CostResetPeriod period) {
        StringBuilder sb = new StringBuilder();
        sb.append("A new review cannot be started right now because the overall ");
        if (period == CostResetPeriod.DAILY) {
            sb.append("daily ");
        } else if (period == CostResetPeriod.MONTHLY) {
            sb.append("monthly ");
        }
        sb.append("spending limit has been reached. ");

        if (period == CostResetPeriod.DAILY) {
            sb.append("So far today the platform has used ");
        } else if (period == CostResetPeriod.MONTHLY) {
            sb.append("So far this month the platform has used ");
        } else {
            sb.append("So far the platform has used ");
        }
        sb.append(formatUsd(currentSpend))
                .append(" of the ")
                .append(formatUsd(limitAmount));
        if (period == CostResetPeriod.DAILY) {
            sb.append(" daily limit. The daily total resets at midnight UTC,"
                    + " so new reviews will resume then."
                    + " An administrator can also raise the limit at any time.");
        } else if (period == CostResetPeriod.MONTHLY) {
            sb.append(" monthly limit. The monthly total resets at the start of next month (UTC),"
                    + " so new reviews will resume then."
                    + " An administrator can also raise the limit at any time.");
        } else {
            sb.append(" limit. New reviews will resume only when an administrator raises the limit.");
        }
        return sb.toString();
    }

    private static String formatUsd(BigDecimal value) {
        BigDecimal rounded = (value != null ? value : BigDecimal.ZERO)
                .setScale(2, RoundingMode.HALF_UP);
        return "$" + rounded.toPlainString();
    }
}
