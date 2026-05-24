package com.sitemanager.model;

import com.sitemanager.model.enums.RecommendationRunStatus;
import jakarta.persistence.*;

import java.time.Instant;

/**
 * One AI-generated recommendation run requested by an admin. Stored
 * permanently so runs survive restart and can be browsed in history.
 * The generated bullets are stored as {@link RecommendationResult} rows
 * linked back to this run via {@code run_id}.
 */
@Entity
@Table(name = "recommendation_runs")
public class RecommendationRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Public identifier returned to the client for status polling. */
    @Column(nullable = false, unique = true, length = 64)
    private String taskId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private RecommendationRunStatus status;

    @Column(columnDefinition = "TEXT")
    private String errorMessage;

    private Long requestedByUserId;

    private String requestedByUsername;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    private Instant completedAt;

    public RecommendationRun() {}

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
        if (status == null) status = RecommendationRunStatus.PENDING;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getTaskId() { return taskId; }
    public void setTaskId(String taskId) { this.taskId = taskId; }

    public RecommendationRunStatus getStatus() { return status; }
    public void setStatus(RecommendationRunStatus status) { this.status = status; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public Long getRequestedByUserId() { return requestedByUserId; }
    public void setRequestedByUserId(Long requestedByUserId) { this.requestedByUserId = requestedByUserId; }

    public String getRequestedByUsername() { return requestedByUsername; }
    public void setRequestedByUsername(String requestedByUsername) { this.requestedByUsername = requestedByUsername; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }
}
