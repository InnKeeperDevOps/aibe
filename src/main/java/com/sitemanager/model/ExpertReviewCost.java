package com.sitemanager.model;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One cost record for a single expert review run. Captures the model used,
 * how many tokens were consumed (input, output, and cache flows), the dollar
 * cost reported by the CLI, and how long the review took. Each row is the
 * raw data point that per-suggestion and global cost roll-ups read from.
 */
@Entity
@Table(name = "expert_review_costs",
        indexes = {
                @Index(name = "idx_expert_review_costs_suggestion", columnList = "suggestionId"),
                @Index(name = "idx_expert_review_costs_created_at", columnList = "createdAt")
        })
public class ExpertReviewCost {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Suggestion this review belongs to. */
    @Column(nullable = false)
    private Long suggestionId;

    /** Display name of the expert role that ran the review (e.g. "QA Engineer"). */
    @Column(nullable = false, length = 100)
    private String expertName;

    /**
     * Operation tag for the underlying CLI call — e.g. {@code expert-review:QA Engineer}
     * or {@code review-feedback:Software Architect<-QA Engineer}. Lets the cost
     * record be traced back to a {@link ClaudeCliLog} entry.
     */
    @Column(length = 255)
    private String operationType;

    /** In-memory review session ID used by the calling service. */
    @Column(length = 100)
    private String reviewSessionId;

    /** Model identifier as resolved at call time. */
    @Column(length = 255)
    private String model;

    @Column(nullable = false)
    private long inputTokens;

    @Column(nullable = false)
    private long outputTokens;

    @Column(nullable = false)
    private long cacheReadInputTokens;

    @Column(nullable = false)
    private long cacheCreationInputTokens;

    /** Dollar cost reported by the CLI. */
    @Column(nullable = false, precision = 19, scale = 6)
    private BigDecimal costUsd = BigDecimal.ZERO;

    /** Wall-clock duration the CLI reported for this review. */
    @Column(nullable = false)
    private long durationMs;

    @Column(nullable = false)
    private Instant createdAt;

    public ExpertReviewCost() {}

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        if (costUsd == null) {
            costUsd = BigDecimal.ZERO;
        }
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getSuggestionId() { return suggestionId; }
    public void setSuggestionId(Long suggestionId) { this.suggestionId = suggestionId; }

    public String getExpertName() { return expertName; }
    public void setExpertName(String expertName) { this.expertName = expertName; }

    public String getOperationType() { return operationType; }
    public void setOperationType(String operationType) { this.operationType = operationType; }

    public String getReviewSessionId() { return reviewSessionId; }
    public void setReviewSessionId(String reviewSessionId) { this.reviewSessionId = reviewSessionId; }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public long getInputTokens() { return inputTokens; }
    public void setInputTokens(long inputTokens) { this.inputTokens = inputTokens; }

    public long getOutputTokens() { return outputTokens; }
    public void setOutputTokens(long outputTokens) { this.outputTokens = outputTokens; }

    public long getCacheReadInputTokens() { return cacheReadInputTokens; }
    public void setCacheReadInputTokens(long cacheReadInputTokens) {
        this.cacheReadInputTokens = cacheReadInputTokens;
    }

    public long getCacheCreationInputTokens() { return cacheCreationInputTokens; }
    public void setCacheCreationInputTokens(long cacheCreationInputTokens) {
        this.cacheCreationInputTokens = cacheCreationInputTokens;
    }

    public BigDecimal getCostUsd() { return costUsd; }
    public void setCostUsd(BigDecimal costUsd) {
        this.costUsd = costUsd != null ? costUsd : BigDecimal.ZERO;
    }

    public long getDurationMs() { return durationMs; }
    public void setDurationMs(long durationMs) { this.durationMs = durationMs; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    /** Total token volume across input, output, and both cache flows. */
    public long getTotalTokens() {
        return inputTokens + outputTokens + cacheReadInputTokens + cacheCreationInputTokens;
    }
}
