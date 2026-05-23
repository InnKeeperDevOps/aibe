package com.sitemanager.model;

import com.sitemanager.model.enums.RecommendationResultStatus;
import jakarta.persistence.*;

/**
 * One generated recommendation (title + description) belonging to a
 * {@link RecommendationRun}. Order is preserved via {@code result_order}
 * so the run can be displayed in the same ranking the AI returned.
 *
 * Once an admin turns a recommendation into a tracked suggestion, the
 * row is left in place but its {@code status} is flipped to ACTED_ON and
 * {@code actedOnSuggestionId} is set, so the active list stops showing it
 * while history retains the full record.
 */
@Entity
@Table(name = "recommendation_results")
public class RecommendationResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long runId;

    @Column(nullable = false)
    private Integer resultOrder;

    @Column(nullable = false)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private RecommendationResultStatus status = RecommendationResultStatus.PENDING;

    private Long actedOnSuggestionId;

    public RecommendationResult() {}

    public RecommendationResult(Long runId, Integer resultOrder, String title, String description) {
        this.runId = runId;
        this.resultOrder = resultOrder;
        this.title = title;
        this.description = description;
        this.status = RecommendationResultStatus.PENDING;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getRunId() { return runId; }
    public void setRunId(Long runId) { this.runId = runId; }

    public Integer getResultOrder() { return resultOrder; }
    public void setResultOrder(Integer resultOrder) { this.resultOrder = resultOrder; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public RecommendationResultStatus getStatus() { return status; }
    public void setStatus(RecommendationResultStatus status) { this.status = status; }

    public Long getActedOnSuggestionId() { return actedOnSuggestionId; }
    public void setActedOnSuggestionId(Long actedOnSuggestionId) { this.actedOnSuggestionId = actedOnSuggestionId; }
}
