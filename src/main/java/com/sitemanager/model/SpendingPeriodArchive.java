package com.sitemanager.model;

import com.sitemanager.model.enums.CostResetPeriod;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Frozen summary of one completed spending window. When the active reset
 * period rolls over (a new UTC day, or a new UTC month) the prior window's
 * total cost and review count are captured here so they remain visible in
 * dashboards and history even though the live windowed query for the new
 * window now returns zero. New work in the fresh window is allowed to
 * proceed at zero — the archive is purely historical, never used by the
 * budget check.
 *
 * <p>The pair {@code (periodType, periodStart)} is unique, which makes
 * archival idempotent under concurrent invocations.
 */
@Entity
@Table(name = "spending_period_archives",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_spending_period_archives",
                columnNames = {"periodType", "periodStart"}),
        indexes = {
                @Index(name = "idx_spending_period_archives_period_start",
                        columnList = "periodStart")
        })
public class SpendingPeriodArchive {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Which kind of window this archive captures (DAILY or MONTHLY). */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CostResetPeriod periodType;

    /** Inclusive start of the archived window, in UTC. */
    @Column(nullable = false)
    private Instant periodStart;

    /** Exclusive end of the archived window, in UTC. */
    @Column(nullable = false)
    private Instant periodEnd;

    /** Total dollar spend recorded inside this window. */
    @Column(nullable = false, precision = 19, scale = 6)
    private BigDecimal totalCostUsd = BigDecimal.ZERO;

    /** Number of reviews recorded inside this window. */
    @Column(nullable = false)
    private long totalReviews;

    /**
     * Global spending cap that was in effect at the moment the window was
     * archived. {@code null} when no cap was configured. Stored so the
     * history view can show "spent $X of $Y" for the prior period even if
     * an admin later changes the cap.
     */
    @Column(precision = 19, scale = 6)
    private BigDecimal limitAtPeriodEnd;

    /** When the archive row was written. */
    @Column(nullable = false)
    private Instant archivedAt;

    public SpendingPeriodArchive() {}

    public SpendingPeriodArchive(CostResetPeriod periodType, Instant periodStart,
                                 Instant periodEnd, BigDecimal totalCostUsd,
                                 long totalReviews, BigDecimal limitAtPeriodEnd) {
        this.periodType = periodType;
        this.periodStart = periodStart;
        this.periodEnd = periodEnd;
        this.totalCostUsd = totalCostUsd != null ? totalCostUsd : BigDecimal.ZERO;
        this.totalReviews = totalReviews;
        this.limitAtPeriodEnd = limitAtPeriodEnd;
    }

    @PrePersist
    protected void onCreate() {
        if (archivedAt == null) archivedAt = Instant.now();
        if (totalCostUsd == null) totalCostUsd = BigDecimal.ZERO;
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
    public void setTotalCostUsd(BigDecimal v) {
        this.totalCostUsd = v != null ? v : BigDecimal.ZERO;
    }
    public long getTotalReviews() { return totalReviews; }
    public void setTotalReviews(long v) { this.totalReviews = v; }
    public BigDecimal getLimitAtPeriodEnd() { return limitAtPeriodEnd; }
    public void setLimitAtPeriodEnd(BigDecimal v) { this.limitAtPeriodEnd = v; }
    public Instant getArchivedAt() { return archivedAt; }
    public void setArchivedAt(Instant v) { this.archivedAt = v; }
}
