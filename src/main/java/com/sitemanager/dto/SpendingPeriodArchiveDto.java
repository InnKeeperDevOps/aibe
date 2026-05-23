package com.sitemanager.dto;

import com.sitemanager.model.SpendingPeriodArchive;
import com.sitemanager.model.enums.CostResetPeriod;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;

/**
 * One entry in the spending history: the kind of window, when it ran from
 * and to (UTC), the total cost and review count that were recorded inside
 * it, and what the cap was at the moment the window closed.
 *
 * <p>Every numeric value is paired with a display string so the dashboard
 * can render the row directly without repeating the same formatting code.
 * The display label ({@code "2026-05-22"} for daily, {@code "2026-05"} for
 * monthly) is included so consumers can show a human-friendly summary
 * without recomputing it from the timestamps.
 */
public class SpendingPeriodArchiveDto {

    private Long id;
    private CostResetPeriod periodType;
    private Instant periodStart;
    private Instant periodEnd;
    private BigDecimal totalCostUsd;
    private long totalReviews;
    private BigDecimal limitAtPeriodEnd;
    private Instant archivedAt;
    private String periodLabel;
    private String displayTotalCost;
    private String displayLimit;
    private Integer percentUsed;

    public SpendingPeriodArchiveDto() {}

    public static SpendingPeriodArchiveDto fromEntity(SpendingPeriodArchive entity) {
        if (entity == null) return null;
        SpendingPeriodArchiveDto dto = new SpendingPeriodArchiveDto();
        dto.id = entity.getId();
        dto.periodType = entity.getPeriodType();
        dto.periodStart = entity.getPeriodStart();
        dto.periodEnd = entity.getPeriodEnd();
        BigDecimal total = entity.getTotalCostUsd() != null
                ? entity.getTotalCostUsd() : BigDecimal.ZERO;
        dto.totalCostUsd = total;
        dto.totalReviews = entity.getTotalReviews();
        dto.limitAtPeriodEnd = entity.getLimitAtPeriodEnd();
        dto.archivedAt = entity.getArchivedAt();
        dto.periodLabel = buildLabel(entity.getPeriodType(), entity.getPeriodStart());
        dto.displayTotalCost = formatUsd(total);
        dto.displayLimit = entity.getLimitAtPeriodEnd() != null
                ? formatUsd(entity.getLimitAtPeriodEnd()) : "No limit";
        dto.percentUsed = computePercent(total, entity.getLimitAtPeriodEnd());
        return dto;
    }

    static String buildLabel(CostResetPeriod periodType, Instant periodStart) {
        if (periodStart == null || periodType == null) return "";
        if (periodType == CostResetPeriod.DAILY) {
            return LocalDate.ofInstant(periodStart, ZoneOffset.UTC).toString();
        }
        if (periodType == CostResetPeriod.MONTHLY) {
            return YearMonth.from(periodStart.atZone(ZoneOffset.UTC).toLocalDate()).toString();
        }
        return periodStart.toString();
    }

    private static Integer computePercent(BigDecimal spend, BigDecimal limit) {
        if (limit == null || limit.signum() <= 0 || spend == null) return null;
        BigDecimal pct = spend.multiply(BigDecimal.valueOf(100))
                .divide(limit, 0, RoundingMode.HALF_UP);
        if (pct.signum() < 0) return 0;
        return pct.intValueExact();
    }

    private static String formatUsd(BigDecimal value) {
        BigDecimal rounded = (value != null ? value : BigDecimal.ZERO)
                .setScale(2, RoundingMode.HALF_UP);
        return "$" + rounded.toPlainString();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public CostResetPeriod getPeriodType() { return periodType; }
    public void setPeriodType(CostResetPeriod v) { this.periodType = v; }
    public Instant getPeriodStart() { return periodStart; }
    public void setPeriodStart(Instant v) { this.periodStart = v; }
    public Instant getPeriodEnd() { return periodEnd; }
    public void setPeriodEnd(Instant v) { this.periodEnd = v; }
    public BigDecimal getTotalCostUsd() { return totalCostUsd; }
    public void setTotalCostUsd(BigDecimal v) { this.totalCostUsd = v; }
    public long getTotalReviews() { return totalReviews; }
    public void setTotalReviews(long v) { this.totalReviews = v; }
    public BigDecimal getLimitAtPeriodEnd() { return limitAtPeriodEnd; }
    public void setLimitAtPeriodEnd(BigDecimal v) { this.limitAtPeriodEnd = v; }
    public Instant getArchivedAt() { return archivedAt; }
    public void setArchivedAt(Instant v) { this.archivedAt = v; }
    public String getPeriodLabel() { return periodLabel; }
    public void setPeriodLabel(String v) { this.periodLabel = v; }
    public String getDisplayTotalCost() { return displayTotalCost; }
    public void setDisplayTotalCost(String v) { this.displayTotalCost = v; }
    public String getDisplayLimit() { return displayLimit; }
    public void setDisplayLimit(String v) { this.displayLimit = v; }
    public Integer getPercentUsed() { return percentUsed; }
    public void setPercentUsed(Integer v) { this.percentUsed = v; }
}
