package com.sitemanager.controller;

import com.sitemanager.model.PlanTask;
import com.sitemanager.model.Suggestion;
import com.sitemanager.model.enums.TaskStatus;
import com.sitemanager.repository.PlanTaskRepository;
import com.sitemanager.repository.SuggestionRepository;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Cross-plan views for the admin UI: a list of every plan (suggestion that has
 * tasks) and a flat task table across all plans. Requires the caller to be
 * logged in.
 */
@RestController
@RequestMapping("/api/plans")
public class PlanController {

    private final SuggestionRepository suggestionRepository;
    private final PlanTaskRepository planTaskRepository;

    public PlanController(SuggestionRepository suggestionRepository,
                          PlanTaskRepository planTaskRepository) {
        this.suggestionRepository = suggestionRepository;
        this.planTaskRepository = planTaskRepository;
    }

    /** All plans (one row per suggestion that has at least one task). */
    @GetMapping
    public ResponseEntity<?> list(HttpSession session) {
        if (session.getAttribute("userId") == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Login required"));
        }

        Map<Long, List<PlanTask>> tasksBySuggestion = new HashMap<>();
        for (PlanTask t : planTaskRepository.findAll()) {
            tasksBySuggestion.computeIfAbsent(t.getSuggestionId(), k -> new ArrayList<>()).add(t);
        }

        List<Map<String, Object>> plans = new ArrayList<>();
        for (Suggestion s : suggestionRepository.findAll()) {
            List<PlanTask> tasks = tasksBySuggestion.get(s.getId());
            if (tasks == null || tasks.isEmpty()) continue;

            int total = tasks.size();
            int completed = 0, inProgress = 0, failed = 0, reviewing = 0;
            for (PlanTask t : tasks) {
                switch (t.getStatus()) {
                    case COMPLETED -> completed++;
                    case IN_PROGRESS -> inProgress++;
                    case REVIEWING -> reviewing++;
                    case FAILED -> failed++;
                    default -> { /* PENDING */ }
                }
            }

            Map<String, Object> row = new HashMap<>();
            row.put("suggestionId", s.getId());
            row.put("title", s.getTitle());
            row.put("suggestionStatus", s.getStatus().name());
            row.put("currentPhase", s.getCurrentPhase());
            row.put("authorName", s.getAuthorName());
            row.put("createdAt", s.getCreatedAt());
            row.put("lastActivityAt", s.getLastActivityAt());
            row.put("totalTasks", total);
            row.put("completedTasks", completed);
            row.put("inProgressTasks", inProgress);
            row.put("reviewingTasks", reviewing);
            row.put("failedTasks", failed);
            row.put("progressPct", (int) Math.round((completed * 100.0) / total));
            plans.add(row);
        }

        plans.sort((a, b) -> {
            Object av = a.get("lastActivityAt"), bv = b.get("lastActivityAt");
            if (av == null && bv == null) return 0;
            if (av == null) return 1;
            if (bv == null) return -1;
            return ((java.time.Instant) bv).compareTo((java.time.Instant) av);
        });

        return ResponseEntity.ok(plans);
    }

    /** All tasks across all plans, joined with their parent suggestion title/status. */
    @GetMapping("/tasks")
    public ResponseEntity<?> allTasks(HttpSession session) {
        if (session.getAttribute("userId") == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Login required"));
        }

        Map<Long, Suggestion> byId = new HashMap<>();
        for (Suggestion s : suggestionRepository.findAll()) {
            byId.put(s.getId(), s);
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        for (PlanTask t : planTaskRepository.findAll()) {
            Suggestion s = byId.get(t.getSuggestionId());
            Map<String, Object> row = new HashMap<>();
            row.put("id", t.getId());
            row.put("suggestionId", t.getSuggestionId());
            row.put("suggestionTitle", s == null ? null : s.getTitle());
            row.put("suggestionStatus", s == null ? null : s.getStatus().name());
            row.put("taskOrder", t.getTaskOrder());
            row.put("title", t.getTitle());
            row.put("displayTitle", t.getDisplayTitle());
            row.put("status", t.getStatus().name());
            row.put("statusDetail", t.getStatusDetail());
            row.put("estimatedMinutes", t.getEstimatedMinutes());
            row.put("startedAt", t.getStartedAt());
            row.put("completedAt", t.getCompletedAt());
            row.put("retryCount", t.getRetryCount());
            row.put("failureReason", t.getFailureReason());
            rows.add(row);
        }

        rows.sort(Comparator.comparing(
                (Map<String, Object> r) -> (java.time.Instant) r.get("startedAt"),
                Comparator.nullsLast(Comparator.reverseOrder())));

        return ResponseEntity.ok(rows);
    }

    /** Full plan detail for a single suggestion: metadata + ordered task list. */
    @GetMapping("/{suggestionId}")
    public ResponseEntity<?> detail(@PathVariable Long suggestionId, HttpSession session) {
        if (session.getAttribute("userId") == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Login required"));
        }
        Suggestion s = suggestionRepository.findById(suggestionId).orElse(null);
        if (s == null) {
            return ResponseEntity.status(404).body(Map.of("error", "Suggestion not found"));
        }
        List<PlanTask> tasks = planTaskRepository.findBySuggestionIdOrderByTaskOrder(suggestionId);

        Map<String, Object> body = new HashMap<>();
        body.put("suggestionId", s.getId());
        body.put("title", s.getTitle());
        body.put("description", s.getDescription());
        body.put("suggestionStatus", s.getStatus().name());
        body.put("currentPhase", s.getCurrentPhase());
        body.put("authorName", s.getAuthorName());
        body.put("planSummary", s.getPlanSummary());
        body.put("planDisplaySummary", s.getPlanDisplaySummary());
        body.put("createdAt", s.getCreatedAt());
        body.put("updatedAt", s.getUpdatedAt());
        body.put("lastActivityAt", s.getLastActivityAt());
        body.put("prUrl", s.getPrUrl());
        body.put("prNumber", s.getPrNumber());
        body.put("claudeSessionId", s.getClaudeSessionId());
        body.put("workingDirectory", s.getWorkingDirectory());
        body.put("expertReviewStep", s.getExpertReviewStep());
        body.put("expertReviewRound", s.getExpertReviewRound());
        body.put("totalExpertReviewRounds", s.getTotalExpertReviewRounds());
        body.put("expertReviewNotes", s.getExpertReviewNotes());
        body.put("expertReviewPlanChanged", s.getExpertReviewPlanChanged());
        body.put("failureReason", s.getFailureReason());
        body.put("tasks", tasks);
        return ResponseEntity.ok(body);
    }

    @SuppressWarnings("unused")
    private static TaskStatus parseStatus(String s) {
        try { return TaskStatus.valueOf(s); } catch (Exception e) { return null; }
    }
}
