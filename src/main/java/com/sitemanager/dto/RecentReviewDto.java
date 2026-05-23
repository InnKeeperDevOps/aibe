package com.sitemanager.dto;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

/**
 * One entry in the "recent reviews" feed on the admin dashboard: a single
 * recorded review with its suggestion, expert role, token count, cost,
 * and how long the underlying CLI call took.
 *
 * <p>The suggestion title is sanitized by the producer before being stored
 * here so the dashboard can render it inline without further escaping.
 */
public class RecentReviewDto {

    private Long id;
    private Long suggestionId;
    private String suggestionTitle;
    private String expertName;
    private long totalTokens;
    private BigDecimal costUsd;
    private String displayCostUsd;
    private long durationMs;
    private Instant createdAt;

    public RecentReviewDto() {}

    public RecentReviewDto(Long id, Long suggestionId, String suggestionTitle,
                           String expertName, long totalTokens, BigDecimal costUsd,
                           long durationMs, Instant createdAt) {
        this.id = id;
        this.suggestionId = suggestionId;
        this.suggestionTitle = suggestionTitle;
        this.expertName = expertName;
        this.totalTokens = totalTokens;
        this.costUsd = costUsd != null ? costUsd : BigDecimal.ZERO;
        this.displayCostUsd = formatUsd(this.costUsd);
        this.durationMs = durationMs;
        this.createdAt = createdAt;
    }

    private static String formatUsd(BigDecimal value) {
        BigDecimal rounded = (value != null ? value : BigDecimal.ZERO)
                .setScale(4, RoundingMode.HALF_UP);
        return "$" + rounded.toPlainString();
    }

    public Long getId() { return id; }
    public void setId(Long v) { this.id = v; }

    public Long getSuggestionId() { return suggestionId; }
    public void setSuggestionId(Long v) { this.suggestionId = v; }

    public String getSuggestionTitle() { return suggestionTitle; }
    public void setSuggestionTitle(String v) { this.suggestionTitle = v; }

    public String getExpertName() { return expertName; }
    public void setExpertName(String v) { this.expertName = v; }

    public long getTotalTokens() { return totalTokens; }
    public void setTotalTokens(long v) { this.totalTokens = v; }

    public BigDecimal getCostUsd() { return costUsd; }
    public void setCostUsd(BigDecimal v) { this.costUsd = v; }

    public String getDisplayCostUsd() { return displayCostUsd; }
    public void setDisplayCostUsd(String v) { this.displayCostUsd = v; }

    public long getDurationMs() { return durationMs; }
    public void setDurationMs(long v) { this.durationMs = v; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant v) { this.createdAt = v; }
}
