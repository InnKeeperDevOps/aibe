package com.sitemanager.dto;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * A snapshot of cost totals at either the per-suggestion or system-wide
 * level. Carries the raw dollar amount, a display-friendly four-decimal
 * version, the total tokens used, and the number of expert reviews behind
 * the totals — enough for dashboards to show running spend at a glance and
 * for real-time updates to refresh those displays.
 *
 * <p>When the underlying ledger has no entries, {@link #totalCostUsd} is
 * {@link BigDecimal#ZERO} (never {@code null}) and {@link #reviewCount} is
 * zero, so consumers never have to defend against missing data.
 */
public class CostSummaryDto {

    /**
     * {@code null} for global rollups; the suggestion id for per-suggestion
     * rollups so the recipient can route the value to the right UI element.
     */
    private Long suggestionId;

    private long reviewCount;
    private long totalTokens;
    private BigDecimal totalCostUsd;

    /** Convenience label like {@code "$0.1234"} for dashboards and emails. */
    private String displayCostUsd;

    public CostSummaryDto() {
        this.totalCostUsd = BigDecimal.ZERO;
        this.displayCostUsd = formatDisplay(BigDecimal.ZERO);
    }

    public CostSummaryDto(Long suggestionId, long reviewCount, long totalTokens,
                          BigDecimal totalCostUsd) {
        this.suggestionId = suggestionId;
        this.reviewCount = reviewCount;
        this.totalTokens = totalTokens;
        this.totalCostUsd = totalCostUsd != null ? totalCostUsd : BigDecimal.ZERO;
        this.displayCostUsd = formatDisplay(this.totalCostUsd);
    }

    public Long getSuggestionId() { return suggestionId; }
    public void setSuggestionId(Long suggestionId) { this.suggestionId = suggestionId; }

    public long getReviewCount() { return reviewCount; }
    public void setReviewCount(long reviewCount) { this.reviewCount = reviewCount; }

    public long getTotalTokens() { return totalTokens; }
    public void setTotalTokens(long totalTokens) { this.totalTokens = totalTokens; }

    public BigDecimal getTotalCostUsd() { return totalCostUsd; }
    public void setTotalCostUsd(BigDecimal totalCostUsd) {
        this.totalCostUsd = totalCostUsd != null ? totalCostUsd : BigDecimal.ZERO;
        this.displayCostUsd = formatDisplay(this.totalCostUsd);
    }

    public String getDisplayCostUsd() { return displayCostUsd; }
    public void setDisplayCostUsd(String displayCostUsd) { this.displayCostUsd = displayCostUsd; }

    private static String formatDisplay(BigDecimal value) {
        BigDecimal rounded = (value != null ? value : BigDecimal.ZERO)
                .setScale(4, RoundingMode.HALF_UP);
        return "$" + rounded.toPlainString();
    }
}
