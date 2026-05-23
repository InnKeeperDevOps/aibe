package com.sitemanager.model;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One row records that a given spending alert has been delivered for a
 * given budget window, so the same threshold does not fire repeatedly while
 * spend hovers around it. The composite of scope, scope key, window key,
 * and threshold uniquely identifies an alert event:
 *
 * <ul>
 *   <li><b>scope</b> — {@code PER_SUGGESTION} or {@code GLOBAL}.</li>
 *   <li><b>scopeKey</b> — the suggestion id for per-suggestion alerts, or a
 *       constant placeholder for global alerts (per-suggestion totals never
 *       reset, so the per-suggestion scope key alone is enough).</li>
 *   <li><b>windowKey</b> — identifies the budget window. For the global
 *       cap it encodes the reset period plus the boundary (e.g.
 *       {@code DAILY:2026-05-23} or {@code MONTHLY:2026-05}). For
 *       per-suggestion alerts the window key is just {@code LIFETIME}.</li>
 *   <li><b>thresholdPercent</b> — the percent of the cap that triggered the
 *       alert, or {@code 100} for the "cap reached" event.</li>
 * </ul>
 *
 * <p>When the underlying window rolls over (a new day, a new month, a new
 * limit value) the {@code windowKey} changes and the same threshold is
 * eligible to fire again. Per-suggestion alert rows are cleared when the
 * per-suggestion limit is changed so admins are notified again under the
 * new ceiling.
 */
@Entity
@Table(name = "spending_alert_state",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_spending_alert_state",
                columnNames = {"scope", "scopeKey", "windowKey", "thresholdPercent"}),
        indexes = {
                @Index(name = "idx_spending_alert_state_scope_key",
                        columnList = "scope,scopeKey,windowKey")
        })
public class SpendingAlertState {

    public enum Scope {
        PER_SUGGESTION,
        GLOBAL
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Scope scope;

    /**
     * Suggestion id (for per-suggestion alerts) or a constant placeholder
     * for the global scope. Kept as a string so both kinds can share one
     * column and one uniqueness constraint.
     */
    @Column(nullable = false, length = 64)
    private String scopeKey;

    /** Identifier of the budget window this alert was fired in. */
    @Column(nullable = false, length = 64)
    private String windowKey;

    /**
     * The percent-of-cap threshold that fired. {@code 100} represents the
     * "cap reached" event distinct from any warning thresholds.
     */
    @Column(nullable = false)
    private int thresholdPercent;

    /**
     * Spend value at the moment the alert was fired, recorded so the audit
     * trail captures real numbers (and not just "we sent something").
     */
    @Column(nullable = false, precision = 19, scale = 6)
    private BigDecimal spendAtAlert = BigDecimal.ZERO;

    /** Configured cap at the moment the alert was fired. */
    @Column(nullable = false, precision = 19, scale = 6)
    private BigDecimal limitAtAlert = BigDecimal.ZERO;

    @Column(nullable = false)
    private Instant firedAt;

    public SpendingAlertState() {}

    public SpendingAlertState(Scope scope, String scopeKey, String windowKey,
                              int thresholdPercent, BigDecimal spendAtAlert,
                              BigDecimal limitAtAlert) {
        this.scope = scope;
        this.scopeKey = scopeKey;
        this.windowKey = windowKey;
        this.thresholdPercent = thresholdPercent;
        this.spendAtAlert = spendAtAlert != null ? spendAtAlert : BigDecimal.ZERO;
        this.limitAtAlert = limitAtAlert != null ? limitAtAlert : BigDecimal.ZERO;
    }

    @PrePersist
    protected void onCreate() {
        if (firedAt == null) firedAt = Instant.now();
        if (spendAtAlert == null) spendAtAlert = BigDecimal.ZERO;
        if (limitAtAlert == null) limitAtAlert = BigDecimal.ZERO;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Scope getScope() { return scope; }
    public void setScope(Scope scope) { this.scope = scope; }
    public String getScopeKey() { return scopeKey; }
    public void setScopeKey(String scopeKey) { this.scopeKey = scopeKey; }
    public String getWindowKey() { return windowKey; }
    public void setWindowKey(String windowKey) { this.windowKey = windowKey; }
    public int getThresholdPercent() { return thresholdPercent; }
    public void setThresholdPercent(int thresholdPercent) { this.thresholdPercent = thresholdPercent; }
    public BigDecimal getSpendAtAlert() { return spendAtAlert; }
    public void setSpendAtAlert(BigDecimal spendAtAlert) {
        this.spendAtAlert = spendAtAlert != null ? spendAtAlert : BigDecimal.ZERO;
    }
    public BigDecimal getLimitAtAlert() { return limitAtAlert; }
    public void setLimitAtAlert(BigDecimal limitAtAlert) {
        this.limitAtAlert = limitAtAlert != null ? limitAtAlert : BigDecimal.ZERO;
    }
    public Instant getFiredAt() { return firedAt; }
    public void setFiredAt(Instant firedAt) { this.firedAt = firedAt; }
}
