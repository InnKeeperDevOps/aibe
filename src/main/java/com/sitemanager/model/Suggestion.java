package com.sitemanager.model;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitemanager.model.enums.Priority;
import com.sitemanager.model.enums.SuggestionStatus;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Entity
@Table(name = "suggestions")
public class Suggestion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SuggestionStatus status = SuggestionStatus.DRAFT;

    @Column
    private Long authorId;

    @Column
    private String authorName;

    @Column(columnDefinition = "TEXT")
    private String planSummary;

    @Column(columnDefinition = "TEXT")
    private String planDisplaySummary;

    @Column
    private String currentPhase;

    @Column
    private String claudeSessionId;

    @Column
    private int upVotes = 0;

    @Column
    private int downVotes = 0;

    @Column
    private String workingDirectory;

    @Column(columnDefinition = "TEXT")
    private String pendingClarificationQuestions;

    @Column
    private String prUrl;

    @Column
    private Integer prNumber;

    @Column(columnDefinition = "TEXT")
    private String changelogEntry;

    @Column
    private Integer expertReviewStep;

    @Column
    private Integer expertReviewRound;

    @Column(columnDefinition = "TEXT")
    private String expertReviewNotes;

    @Column
    private Boolean expertReviewPlanChanged;

    @Column
    private Integer totalExpertReviewRounds;

    @Column(columnDefinition = "TEXT")
    private String expertReviewChangedDomains;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Column
    private Instant lastActivityAt;

    @Enumerated(EnumType.STRING)
    @Column(length = 10, nullable = false)
    private Priority priority = Priority.MEDIUM;

    @Column(columnDefinition = "TEXT")
    private String ownerLockedPlanSections;

    @Column(columnDefinition = "TEXT")
    private String expertApprovalTracker;

    @Column(length = 1000)
    private String failureReason;

    @Column(name = "experts_approved_current_plan", nullable = false)
    private boolean expertsApprovedCurrentPlan = false;

    @Column(name = "last_clarification_answers", columnDefinition = "TEXT")
    private String lastClarificationAnswers;

    public Suggestion() {}

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
        if (lastActivityAt == null) lastActivityAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public SuggestionStatus getStatus() { return status; }
    public void setStatus(SuggestionStatus status) { this.status = status; }
    public Long getAuthorId() { return authorId; }
    public void setAuthorId(Long authorId) { this.authorId = authorId; }
    public String getAuthorName() { return authorName; }
    public void setAuthorName(String authorName) { this.authorName = authorName; }
    public String getPlanSummary() { return planSummary; }
    public void setPlanSummary(String planSummary) { this.planSummary = planSummary; }
    public String getPlanDisplaySummary() { return planDisplaySummary; }
    public void setPlanDisplaySummary(String planDisplaySummary) { this.planDisplaySummary = planDisplaySummary; }
    public String getCurrentPhase() { return currentPhase; }
    public void setCurrentPhase(String currentPhase) { this.currentPhase = currentPhase; }
    public String getClaudeSessionId() { return claudeSessionId; }
    public void setClaudeSessionId(String claudeSessionId) { this.claudeSessionId = claudeSessionId; }
    public int getUpVotes() { return upVotes; }
    public void setUpVotes(int upVotes) { this.upVotes = upVotes; }
    public int getDownVotes() { return downVotes; }
    public void setDownVotes(int downVotes) { this.downVotes = downVotes; }
    public String getWorkingDirectory() { return workingDirectory; }
    public void setWorkingDirectory(String workingDirectory) { this.workingDirectory = workingDirectory; }
    public String getPendingClarificationQuestions() { return pendingClarificationQuestions; }
    public void setPendingClarificationQuestions(String pendingClarificationQuestions) { this.pendingClarificationQuestions = pendingClarificationQuestions; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public Instant getLastActivityAt() { return lastActivityAt; }
    public void setLastActivityAt(Instant lastActivityAt) { this.lastActivityAt = lastActivityAt; }
    public String getPrUrl() { return prUrl; }
    public void setPrUrl(String prUrl) { this.prUrl = prUrl; }
    public Integer getPrNumber() { return prNumber; }
    public void setPrNumber(Integer prNumber) { this.prNumber = prNumber; }
    public String getChangelogEntry() { return changelogEntry; }
    public void setChangelogEntry(String changelogEntry) { this.changelogEntry = changelogEntry; }
    public Integer getExpertReviewStep() { return expertReviewStep; }
    public void setExpertReviewStep(Integer expertReviewStep) { this.expertReviewStep = expertReviewStep; }
    public Integer getExpertReviewRound() { return expertReviewRound; }
    public void setExpertReviewRound(Integer expertReviewRound) { this.expertReviewRound = expertReviewRound; }
    public String getExpertReviewNotes() { return expertReviewNotes; }
    public void setExpertReviewNotes(String expertReviewNotes) { this.expertReviewNotes = expertReviewNotes; }
    public Boolean getExpertReviewPlanChanged() { return expertReviewPlanChanged; }
    public void setExpertReviewPlanChanged(Boolean expertReviewPlanChanged) { this.expertReviewPlanChanged = expertReviewPlanChanged; }
    public Integer getTotalExpertReviewRounds() { return totalExpertReviewRounds; }
    public void setTotalExpertReviewRounds(Integer totalExpertReviewRounds) { this.totalExpertReviewRounds = totalExpertReviewRounds; }
    public String getExpertReviewChangedDomains() { return expertReviewChangedDomains; }
    public void setExpertReviewChangedDomains(String expertReviewChangedDomains) { this.expertReviewChangedDomains = expertReviewChangedDomains; }
    public Priority getPriority() { return priority; }
    public void setPriority(Priority priority) { this.priority = priority; }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public List<Integer> getOwnerLockedSections() {
        if (ownerLockedPlanSections == null || ownerLockedPlanSections.isBlank()) {
            return Collections.emptyList();
        }
        try {
            return MAPPER.readValue(ownerLockedPlanSections, new TypeReference<List<Integer>>() {});
        } catch (JsonProcessingException e) {
            return Collections.emptyList();
        }
    }

    public String getOwnerLockedPlanSections() { return ownerLockedPlanSections; }
    public void setOwnerLockedPlanSections(String ownerLockedPlanSections) { this.ownerLockedPlanSections = ownerLockedPlanSections; }

    public void setOwnerLockedSections(List<Integer> sections) {
        if (sections == null || sections.isEmpty()) {
            this.ownerLockedPlanSections = null;
            return;
        }
        try {
            this.ownerLockedPlanSections = MAPPER.writeValueAsString(sections);
        } catch (JsonProcessingException e) {
            this.ownerLockedPlanSections = null;
        }
    }

    // --- Expert Approval Tracker ---

    public static class ExpertApprovalEntry {
        private String status;
        private int round;

        public ExpertApprovalEntry() {}

        public ExpertApprovalEntry(String status, int round) {
            this.status = status;
            this.round = round;
        }

        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public int getRound() { return round; }
        public void setRound(int round) { this.round = round; }
    }

    public String getExpertApprovalTracker() { return expertApprovalTracker; }
    public void setExpertApprovalTracker(String expertApprovalTracker) { this.expertApprovalTracker = expertApprovalTracker; }
    public String getFailureReason() { return failureReason; }
    public void setFailureReason(String failureReason) { this.failureReason = failureReason; }
    public boolean isExpertsApprovedCurrentPlan() { return expertsApprovedCurrentPlan; }
    public void setExpertsApprovedCurrentPlan(boolean v) { this.expertsApprovedCurrentPlan = v; }
    public String getLastClarificationAnswers() { return lastClarificationAnswers; }
    public void setLastClarificationAnswers(String v) { this.lastClarificationAnswers = v; }

    public Map<String, ExpertApprovalEntry> getExpertApprovalMap() {
        if (expertApprovalTracker == null || expertApprovalTracker.isBlank()) {
            return new HashMap<>();
        }
        try {
            return MAPPER.readValue(expertApprovalTracker, new TypeReference<Map<String, ExpertApprovalEntry>>() {});
        } catch (JsonProcessingException e) {
            return new HashMap<>();
        }
    }

    public void setExpertApprovalMap(Map<String, ExpertApprovalEntry> map) {
        if (map == null || map.isEmpty()) {
            this.expertApprovalTracker = null;
            return;
        }
        try {
            this.expertApprovalTracker = MAPPER.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            this.expertApprovalTracker = null;
        }
    }

    // --- Expert Review Summary ---

    public static class ExpertReviewEntry {
        private final String expertName;
        private final String status; // APPROVED, FLAGGED, PENDING
        private final String keyPoint;

        public ExpertReviewEntry(String expertName, String status, String keyPoint) {
            this.expertName = expertName;
            this.status = status;
            this.keyPoint = keyPoint;
        }

        public String getExpertName() { return expertName; }
        public String getStatus() { return status; }
        public String getKeyPoint() { return keyPoint; }
    }

    /**
     * Parses expertReviewNotes (format: "**ExpertName**: note\n\n**ExpertName2**: note")
     * and returns a summary of reviewed experts. PENDING entries are not included here;
     * callers should add PENDING for experts not present in the returned list.
     */
    public List<ExpertReviewEntry> getExpertReviewSummary() {
        String notes = this.expertReviewNotes;
        if (notes == null || notes.isBlank()) {
            return new ArrayList<>();
        }

        List<ExpertReviewEntry> result = new ArrayList<>();
        String[] entries = notes.split("\n\n");

        for (String entry : entries) {
            entry = entry.trim();
            if (!entry.startsWith("**")) continue;

            int nameEnd = entry.indexOf("**", 2);
            if (nameEnd < 0) continue;

            String expertName = entry.substring(2, nameEnd).trim();
            String note = entry.substring(nameEnd + 2).trim();
            if (note.startsWith(":")) {
                note = note.substring(1).trim();
            }

            if (expertName.isBlank() || note.isBlank()) continue;

            String lower = note.toLowerCase();
            boolean flagged = lower.contains("concern") || lower.contains("risk") ||
                    lower.contains("vulnerab") || lower.contains("problem") ||
                    lower.contains("issue") || lower.contains("flag");
            String status = flagged ? "FLAGGED" : "APPROVED";

            // Key point: first sentence (up to 150 chars), or truncated note
            String keyPoint;
            int sentenceEnd = note.indexOf(". ");
            if (sentenceEnd > 0 && sentenceEnd < 150) {
                keyPoint = note.substring(0, sentenceEnd + 1);
            } else if (note.length() > 150) {
                keyPoint = note.substring(0, 150) + "...";
            } else {
                keyPoint = note;
            }

            result.add(new ExpertReviewEntry(expertName, status, keyPoint));
        }

        return result;
    }
}
