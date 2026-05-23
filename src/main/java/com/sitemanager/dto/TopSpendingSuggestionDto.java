package com.sitemanager.dto;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * One row in the "top spending suggestions" list on the admin dashboard:
 * a suggestion id, a sanitized title for display, how many reviews have
 * run against it, the total dollar spend, and (when a per-suggestion cap
 * is configured) how much of that cap is left.
 *
 * <p>The title is always sanitized by the producer before being placed
 * here — user-supplied content must never reach the dashboard as raw HTML.
 */
public class TopSpendingSuggestionDto {

    private Long suggestionId;
    private String title;
    private long reviewCount;
    private BigDecimal totalCostUsd;
    private String displayCostUsd;
    private BigDecimal perSuggestionLimitUsd;
    private BigDecimal remainingBudgetUsd;
    private String displayRemaining;
    private Integer percentUsed;

    public TopSpendingSuggestionDto() {}

    public TopSpendingSuggestionDto(Long suggestionId, String title, long reviewCount,
                                    BigDecimal totalCostUsd,
                                    BigDecimal perSuggestionLimitUsd) {
        this.suggestionId = suggestionId;
        this.title = title;
        this.reviewCount = reviewCount;
        this.totalCostUsd = totalCostUsd != null ? totalCostUsd : BigDecimal.ZERO;
        this.displayCostUsd = formatUsd(this.totalCostUsd);
        if (perSuggestionLimitUsd != null && perSuggestionLimitUsd.signum() > 0) {
            this.perSuggestionLimitUsd = perSuggestionLimitUsd;
            BigDecimal remaining = perSuggestionLimitUsd.subtract(this.totalCostUsd);
            if (remaining.signum() < 0) remaining = BigDecimal.ZERO;
            this.remainingBudgetUsd = remaining;
            this.displayRemaining = formatUsd(remaining);
            BigDecimal pct = this.totalCostUsd.multiply(BigDecimal.valueOf(100))
                    .divide(perSuggestionLimitUsd, 0, RoundingMode.HALF_UP);
            this.percentUsed = pct.signum() < 0 ? 0 : pct.intValueExact();
        }
    }

    private static String formatUsd(BigDecimal value) {
        BigDecimal rounded = (value != null ? value : BigDecimal.ZERO)
                .setScale(2, RoundingMode.HALF_UP);
        return "$" + rounded.toPlainString();
    }

    public Long getSuggestionId() { return suggestionId; }
    public void setSuggestionId(Long v) { this.suggestionId = v; }

    public String getTitle() { return title; }
    public void setTitle(String v) { this.title = v; }

    public long getReviewCount() { return reviewCount; }
    public void setReviewCount(long v) { this.reviewCount = v; }

    public BigDecimal getTotalCostUsd() { return totalCostUsd; }
    public void setTotalCostUsd(BigDecimal v) { this.totalCostUsd = v; }

    public String getDisplayCostUsd() { return displayCostUsd; }
    public void setDisplayCostUsd(String v) { this.displayCostUsd = v; }

    public BigDecimal getPerSuggestionLimitUsd() { return perSuggestionLimitUsd; }
    public void setPerSuggestionLimitUsd(BigDecimal v) { this.perSuggestionLimitUsd = v; }

    public BigDecimal getRemainingBudgetUsd() { return remainingBudgetUsd; }
    public void setRemainingBudgetUsd(BigDecimal v) { this.remainingBudgetUsd = v; }

    public String getDisplayRemaining() { return displayRemaining; }
    public void setDisplayRemaining(String v) { this.displayRemaining = v; }

    public Integer getPercentUsed() { return percentUsed; }
    public void setPercentUsed(Integer v) { this.percentUsed = v; }
}
