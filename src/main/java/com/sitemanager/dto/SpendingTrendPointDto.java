package com.sitemanager.dto;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

/**
 * One day in the spending trend chart: the calendar date (UTC) and the
 * dollar spend / review count recorded on that day. The dashboard plots
 * a series of these to give admins a feel for whether spending is steady,
 * accelerating, or has spiked.
 *
 * <p>The producer fills in a point for every day in the requested window,
 * including days with no activity (zero cost, zero reviews), so the chart
 * does not skip gaps and mislead readers about cadence.
 */
public class SpendingTrendPointDto {

    private LocalDate date;
    private BigDecimal totalCostUsd;
    private long reviewCount;
    private String displayCostUsd;

    public SpendingTrendPointDto() {}

    public SpendingTrendPointDto(LocalDate date, BigDecimal totalCostUsd, long reviewCount) {
        this.date = date;
        this.totalCostUsd = totalCostUsd != null ? totalCostUsd : BigDecimal.ZERO;
        this.reviewCount = reviewCount;
        this.displayCostUsd = formatUsd(this.totalCostUsd);
    }

    private static String formatUsd(BigDecimal value) {
        BigDecimal rounded = (value != null ? value : BigDecimal.ZERO)
                .setScale(2, RoundingMode.HALF_UP);
        return "$" + rounded.toPlainString();
    }

    public LocalDate getDate() { return date; }
    public void setDate(LocalDate v) { this.date = v; }

    public BigDecimal getTotalCostUsd() { return totalCostUsd; }
    public void setTotalCostUsd(BigDecimal v) { this.totalCostUsd = v; }

    public long getReviewCount() { return reviewCount; }
    public void setReviewCount(long v) { this.reviewCount = v; }

    public String getDisplayCostUsd() { return displayCostUsd; }
    public void setDisplayCostUsd(String v) { this.displayCostUsd = v; }
}
