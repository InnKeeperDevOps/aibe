package com.sitemanager.dto;

import com.sitemanager.model.enums.CostResetPeriod;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

/**
 * Current state of the global spend cap for the active reset window: how
 * much has been spent so far, what the configured cap is, what is still
 * available, and (for DAILY / MONTHLY windows) when the budget will free
 * up. Dashboards render this directly — every numeric field is paired with
 * a friendly display string so the UI does not have to repeat formatting.
 *
 * <p>When no cap is configured, {@link #limitConfigured} is {@code false}
 * and the limit / remaining / percent fields are {@code null} (rather than
 * arbitrary zero values), so the UI can show "no limit set" instead of a
 * misleading 0%.
 */
public class GlobalCapStatusDto {

    private boolean limitConfigured;
    private BigDecimal currentSpendUsd;
    private BigDecimal limitUsd;
    private BigDecimal remainingBudgetUsd;
    private Integer percentUsed;
    private CostResetPeriod resetPeriod;
    private Instant windowStart;
    private Instant nextResetAt;
    private String displayCurrentSpend;
    private String displayLimit;
    private String displayRemaining;

    public GlobalCapStatusDto() {}

    public static GlobalCapStatusDto unlimited(BigDecimal currentSpend,
                                               CostResetPeriod resetPeriod,
                                               Instant windowStart,
                                               Instant nextResetAt) {
        GlobalCapStatusDto dto = new GlobalCapStatusDto();
        dto.limitConfigured = false;
        dto.currentSpendUsd = nonNull(currentSpend);
        dto.resetPeriod = resetPeriod;
        dto.windowStart = windowStart;
        dto.nextResetAt = nextResetAt;
        dto.displayCurrentSpend = formatUsd(dto.currentSpendUsd);
        dto.displayLimit = "No limit";
        dto.displayRemaining = "Unlimited";
        return dto;
    }

    public static GlobalCapStatusDto enforced(BigDecimal currentSpend,
                                              BigDecimal limit,
                                              CostResetPeriod resetPeriod,
                                              Instant windowStart,
                                              Instant nextResetAt) {
        GlobalCapStatusDto dto = new GlobalCapStatusDto();
        dto.limitConfigured = true;
        dto.currentSpendUsd = nonNull(currentSpend);
        dto.limitUsd = nonNull(limit);
        BigDecimal remaining = dto.limitUsd.subtract(dto.currentSpendUsd);
        // Spend that has overshot the cap (possible because in-flight reviews
        // are never interrupted) should show as zero remaining, not as a
        // negative number.
        if (remaining.signum() < 0) remaining = BigDecimal.ZERO;
        dto.remainingBudgetUsd = remaining;
        dto.percentUsed = computePercent(dto.currentSpendUsd, dto.limitUsd);
        dto.resetPeriod = resetPeriod;
        dto.windowStart = windowStart;
        dto.nextResetAt = nextResetAt;
        dto.displayCurrentSpend = formatUsd(dto.currentSpendUsd);
        dto.displayLimit = formatUsd(dto.limitUsd);
        dto.displayRemaining = formatUsd(dto.remainingBudgetUsd);
        return dto;
    }

    private static BigDecimal nonNull(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }

    private static Integer computePercent(BigDecimal spend, BigDecimal limit) {
        if (limit == null || limit.signum() <= 0) return null;
        BigDecimal pct = spend.multiply(BigDecimal.valueOf(100))
                .divide(limit, 0, RoundingMode.HALF_UP);
        if (pct.signum() < 0) return 0;
        return pct.intValueExact();
    }

    private static String formatUsd(BigDecimal value) {
        BigDecimal rounded = nonNull(value).setScale(2, RoundingMode.HALF_UP);
        return "$" + rounded.toPlainString();
    }

    public boolean isLimitConfigured() { return limitConfigured; }
    public void setLimitConfigured(boolean limitConfigured) { this.limitConfigured = limitConfigured; }

    public BigDecimal getCurrentSpendUsd() { return currentSpendUsd; }
    public void setCurrentSpendUsd(BigDecimal v) { this.currentSpendUsd = v; }

    public BigDecimal getLimitUsd() { return limitUsd; }
    public void setLimitUsd(BigDecimal v) { this.limitUsd = v; }

    public BigDecimal getRemainingBudgetUsd() { return remainingBudgetUsd; }
    public void setRemainingBudgetUsd(BigDecimal v) { this.remainingBudgetUsd = v; }

    public Integer getPercentUsed() { return percentUsed; }
    public void setPercentUsed(Integer v) { this.percentUsed = v; }

    public CostResetPeriod getResetPeriod() { return resetPeriod; }
    public void setResetPeriod(CostResetPeriod v) { this.resetPeriod = v; }

    public Instant getWindowStart() { return windowStart; }
    public void setWindowStart(Instant v) { this.windowStart = v; }

    public Instant getNextResetAt() { return nextResetAt; }
    public void setNextResetAt(Instant v) { this.nextResetAt = v; }

    public String getDisplayCurrentSpend() { return displayCurrentSpend; }
    public void setDisplayCurrentSpend(String v) { this.displayCurrentSpend = v; }

    public String getDisplayLimit() { return displayLimit; }
    public void setDisplayLimit(String v) { this.displayLimit = v; }

    public String getDisplayRemaining() { return displayRemaining; }
    public void setDisplayRemaining(String v) { this.displayRemaining = v; }
}
