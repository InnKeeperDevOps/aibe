package com.sitemanager.controller;

import com.sitemanager.dto.ClarificationRequest;
import com.sitemanager.dto.MessageRequest;
import com.sitemanager.dto.SuggestionRequest;
import com.sitemanager.dto.UpdateDraftRequest;
import com.sitemanager.dto.VoteRequest;
import com.sitemanager.model.PlanTask;
import com.sitemanager.model.Suggestion;
import com.sitemanager.model.SuggestionMessage;
import com.sitemanager.model.enums.Permission;
import com.sitemanager.model.enums.Priority;
import com.sitemanager.model.enums.SuggestionStatus;
import com.sitemanager.service.PermissionService;
import com.sitemanager.service.SiteSettingsService;
import com.sitemanager.service.SuggestionService;
import com.sitemanager.service.VoteService;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/suggestions")
public class SuggestionController {

    private final SuggestionService suggestionService;
    private final VoteService voteService;
    private final SiteSettingsService settingsService;
    private final PermissionService permissionService;

    public SuggestionController(SuggestionService suggestionService, VoteService voteService,
                                SiteSettingsService settingsService, PermissionService permissionService) {
        this.suggestionService = suggestionService;
        this.voteService = voteService;
        this.settingsService = settingsService;
        this.permissionService = permissionService;
    }

    @GetMapping
    public ResponseEntity<List<Suggestion>> list(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String sortBy,
            @RequestParam(required = false) String sortDir,
            @RequestParam(required = false) String priority,
            HttpSession session) {
        String username = (String) session.getAttribute("username");
        if (search == null && status == null && sortBy == null && sortDir == null && priority == null) {
            return ResponseEntity.ok(suggestionService.getAllSuggestions(username));
        }
        return ResponseEntity.ok(suggestionService.getSuggestions(search, status, sortBy, sortDir, priority));
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> get(@PathVariable Long id) {
        return suggestionService.getSuggestion(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{id}/messages")
    public ResponseEntity<List<SuggestionMessage>> getMessages(@PathVariable Long id) {
        return ResponseEntity.ok(suggestionService.getMessages(id));
    }

    @PostMapping
    public ResponseEntity<?> create(@Valid @RequestBody SuggestionRequest request, HttpSession session) {
        String username = (String) session.getAttribute("username");
        Long userId = (Long) session.getAttribute("userId");

        if (username == null) {
            // Anonymous path: check if anonymous suggestions are allowed
            if (!settingsService.getSettings().isAllowAnonymousSuggestions()) {
                return ResponseEntity.status(403)
                        .body(Map.of("error", "Anonymous suggestions are not allowed. Please log in."));
            }
        } else {
            // Logged-in path: check CREATE_SUGGESTIONS permission
            if (!permissionService.hasPermission(session, Permission.CREATE_SUGGESTIONS)) {
                return ResponseEntity.status(403)
                        .body(Map.of("error", "You do not have permission to create suggestions."));
            }
        }

        String authorName = username != null ? username :
                (request.getAuthorName() != null ? request.getAuthorName() : "Anonymous");

        if (request.isDraft() && username == null) {
            return ResponseEntity.status(401)
                    .body(Map.of("error", "You must be logged in to save a draft."));
        }

        Suggestion suggestion = suggestionService.createSuggestion(
                request.getTitle(),
                request.getDescription(),
                userId,
                authorName,
                request.getPriority(),
                request.isDraft()
        );

        return ResponseEntity.ok(suggestion);
    }

    @GetMapping("/my-drafts")
    public ResponseEntity<?> getMyDrafts(HttpSession session) {
        String username = (String) session.getAttribute("username");
        if (username == null) {
            return ResponseEntity.status(401).body(Map.of("error", "You must be logged in to view your drafts."));
        }
        return ResponseEntity.ok(suggestionService.getMyDrafts(username));
    }

    @PatchMapping("/{id}/draft")
    public ResponseEntity<?> updateDraft(@PathVariable Long id,
                                         @Valid @RequestBody UpdateDraftRequest request,
                                         HttpSession session) {
        String username = (String) session.getAttribute("username");
        if (username == null) {
            return ResponseEntity.status(401).body(Map.of("error", "You must be logged in to update a draft."));
        }
        try {
            Suggestion updated = suggestionService.updateDraft(id, request, username);
            return ResponseEntity.ok(updated);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
        } catch (org.springframework.web.server.ResponseStatusException e) {
            return ResponseEntity.status(e.getStatusCode()).body(Map.of("error", e.getReason()));
        }
    }

    @PostMapping("/{id}/submit")
    public ResponseEntity<?> submitDraft(@PathVariable Long id, HttpSession session) {
        String username = (String) session.getAttribute("username");
        if (username == null) {
            return ResponseEntity.status(401).body(Map.of("error", "You must be logged in to submit a draft."));
        }
        try {
            Suggestion submitted = suggestionService.submitDraft(id, username);
            return ResponseEntity.ok(submitted);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
        } catch (org.springframework.web.server.ResponseStatusException e) {
            return ResponseEntity.status(e.getStatusCode()).body(Map.of("error", e.getReason()));
        }
    }

    @PostMapping("/{id}/messages")
    public ResponseEntity<?> reply(@PathVariable Long id, @Valid @RequestBody MessageRequest request,
                                   HttpSession session) {
        if (!permissionService.hasPermission(session, Permission.REPLY)) {
            return ResponseEntity.status(403).body(Map.of("error", "You do not have permission to reply."));
        }

        String username = (String) session.getAttribute("username");
        String senderName = username != null ? username :
                (request.getSenderName() != null ? request.getSenderName() : "Anonymous");

        suggestionService.handleUserReply(id, senderName, request.getContent());
        return ResponseEntity.ok(Map.of("message", "Reply sent"));
    }

    @PostMapping("/{id}/clarifications")
    public ResponseEntity<?> submitClarifications(@PathVariable Long id,
                                                   @Valid @RequestBody ClarificationRequest request,
                                                   HttpSession session) {
        String username = (String) session.getAttribute("username");
        String senderName = username != null ? username :
                (request.getSenderName() != null ? request.getSenderName() : "Anonymous");

        suggestionService.handleClarificationAnswers(id, senderName, request.getAnswers());
        return ResponseEntity.ok(Map.of("message", "Clarification answers submitted"));
    }

    @GetMapping("/{id}/tasks")
    public ResponseEntity<List<PlanTask>> getTasks(@PathVariable Long id) {
        return ResponseEntity.ok(suggestionService.getPlanTasks(id));
    }

    @GetMapping("/{id}/review-summary")
    public ResponseEntity<?> getReviewSummary(@PathVariable Long id) {
        if (suggestionService.getSuggestion(id).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        List<?> summary = suggestionService.getReviewSummary(id);
        if (summary == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(summary);
    }

    @GetMapping("/{id}/expert-review-status")
    public ResponseEntity<?> getExpertReviewStatus(@PathVariable Long id) {
        var status = suggestionService.getExpertReviewStatus(id);
        if (status == null) {
            return ResponseEntity.ok(Map.of());
        }
        return ResponseEntity.ok(status);
    }

    @PostMapping("/{id}/expert-clarifications")
    public ResponseEntity<?> submitExpertClarifications(@PathVariable Long id,
                                                          @Valid @RequestBody ClarificationRequest request,
                                                          HttpSession session) {
        var suggestion = suggestionService.getSuggestion(id);
        if (suggestion.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        if (suggestion.get().getStatus() != SuggestionStatus.EXPERT_REVIEW) {
            return ResponseEntity.status(409)
                    .body(Map.of("error", "Expert reviews cannot be submitted when the suggestion is in "
                            + suggestion.get().getStatus() + " state."));
        }

        String username = (String) session.getAttribute("username");
        String senderName = username != null ? username :
                (request.getSenderName() != null ? request.getSenderName() : "Anonymous");

        suggestionService.handleExpertClarificationAnswers(id, senderName, request.getAnswers());
        return ResponseEntity.ok(Map.of("message", "Expert clarification answers submitted"));
    }

    @GetMapping("/{id}/clarification-questions")
    public ResponseEntity<?> getClarificationQuestions(@PathVariable Long id) {
        java.util.List<String> questions = suggestionService.getPendingQuestions(id);
        if (questions == null || questions.isEmpty()) {
            return ResponseEntity.ok(Map.of("questions", java.util.List.of(), "hasPending", false));
        }
        return ResponseEntity.ok(Map.of("questions", questions, "hasPending", true));
    }

    @GetMapping("/execution-queue")
    public ResponseEntity<?> getExecutionQueue() {
        return ResponseEntity.ok(suggestionService.getExecutionQueueStatus());
    }

    @PostMapping("/{id}/approve")
    public ResponseEntity<?> approve(@PathVariable Long id, HttpSession session) {
        if (!permissionService.hasPermission(session, Permission.APPROVE_DENY_SUGGESTIONS)) {
            return ResponseEntity.status(403).body(Map.of("error", "Admin access required"));
        }
        try {
            return ResponseEntity.ok(suggestionService.approveSuggestion(id));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/{id}/deny")
    public ResponseEntity<?> deny(@PathVariable Long id, @RequestBody(required = false) Map<String, String> body,
                                  HttpSession session) {
        if (!permissionService.hasPermission(session, Permission.APPROVE_DENY_SUGGESTIONS)) {
            return ResponseEntity.status(403).body(Map.of("error", "Admin access required"));
        }
        String reason = body != null ? body.get("reason") : null;
        return ResponseEntity.ok(suggestionService.denySuggestion(id, reason));
    }

    @PostMapping("/{id}/retry-pr")
    public ResponseEntity<?> retryPr(@PathVariable Long id, HttpSession session) {
        if (!permissionService.hasPermission(session, Permission.APPROVE_DENY_SUGGESTIONS)) {
            return ResponseEntity.status(403).body(Map.of("error", "Admin access required"));
        }
        return ResponseEntity.ok(suggestionService.retryPrCreation(id));
    }

    @PostMapping("/{id}/retry-merge")
    public ResponseEntity<?> retryMerge(@PathVariable Long id, HttpSession session) {
        if (!permissionService.hasPermission(session, Permission.APPROVE_DENY_SUGGESTIONS)) {
            return ResponseEntity.status(403).body(Map.of("error", "Admin access required"));
        }
        return ResponseEntity.ok(suggestionService.retryMerge(id));
    }

    @PostMapping("/{id}/retry-from-last")
    public ResponseEntity<?> retryFromLast(@PathVariable Long id, HttpSession session) {
        if (!permissionService.hasPermission(session, Permission.APPROVE_DENY_SUGGESTIONS)) {
            return ResponseEntity.status(403).body(Map.of("error", "Admin access required"));
        }
        try {
            return ResponseEntity.ok(suggestionService.retryFromLastSuccessful(id));
        } catch (org.springframework.web.server.ResponseStatusException e) {
            return ResponseEntity.status(e.getStatusCode()).body(Map.of("error", e.getReason()));
        }
    }

    @PostMapping("/{id}/retry")
    public ResponseEntity<?> retry(@PathVariable Long id, HttpSession session) {
        String username = (String) session.getAttribute("username");
        if (username == null) {
            return ResponseEntity.status(401).body(Map.of("error", "You must be logged in to retry a suggestion."));
        }
        try {
            return ResponseEntity.ok(suggestionService.retrySuggestion(id, username));
        } catch (org.springframework.web.server.ResponseStatusException e) {
            return ResponseEntity.status(e.getStatusCode()).body(Map.of("error", e.getReason()));
        }
    }

    @PostMapping("/{id}/retry-execution")
    public ResponseEntity<?> retryExecution(@PathVariable Long id, HttpSession session) {
        if (!permissionService.hasPermission(session, Permission.APPROVE_DENY_SUGGESTIONS)) {
            return ResponseEntity.status(403).body(Map.of("error", "Admin access required"));
        }
        try {
            return ResponseEntity.ok(suggestionService.retryExecution(id));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/{id}/restart-plan")
    public ResponseEntity<?> restartPlan(@PathVariable Long id, HttpSession session) {
        if (!permissionService.hasPermission(session, Permission.APPROVE_DENY_SUGGESTIONS)) {
            return ResponseEntity.status(403).body(Map.of("error", "Admin access required"));
        }
        try {
            return ResponseEntity.ok(suggestionService.restartPlanExecution(id));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/{id}/approve-plan")
    public ResponseEntity<?> approvePlan(@PathVariable Long id, HttpSession session) {
        if (!permissionService.hasPermission(session, Permission.APPROVE_DENY_SUGGESTIONS)) {
            return ResponseEntity.status(403).body(Map.of("error", "Admin access required"));
        }
        try {
            return ResponseEntity.ok(suggestionService.approvePlan(id));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/{id}/retry-clarification")
    public ResponseEntity<?> retryClarification(@PathVariable Long id, HttpSession session) {
        if (!permissionService.hasPermission(session, Permission.APPROVE_DENY_SUGGESTIONS)) {
            return ResponseEntity.status(403).body(Map.of("error", "Admin access required"));
        }
        try {
            return ResponseEntity.ok(suggestionService.retryClarification(id));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/{id}/request-plan-changes")
    public ResponseEntity<?> requestPlanChanges(@PathVariable Long id,
                                                @RequestBody Map<String, String> body,
                                                HttpSession session) {
        if (!permissionService.hasPermission(session, Permission.APPROVE_DENY_SUGGESTIONS)) {
            return ResponseEntity.status(403).body(Map.of("error", "Admin access required"));
        }
        String feedback = body == null ? null : body.get("feedback");
        if (feedback == null || feedback.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "feedback is required"));
        }
        try {
            return ResponseEntity.ok(suggestionService.requestPlanChanges(id, feedback));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/{id}/restart-from-scratch")
    public ResponseEntity<?> restartFromScratch(@PathVariable Long id, HttpSession session) {
        if (!permissionService.hasPermission(session, Permission.APPROVE_DENY_SUGGESTIONS)) {
            return ResponseEntity.status(403).body(Map.of("error", "Admin access required"));
        }
        try {
            return ResponseEntity.ok(suggestionService.restartFromScratch(id));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/{id}/force-re-approval")
    public ResponseEntity<?> forceReApproval(@PathVariable Long id, HttpSession session) {
        if (!permissionService.hasPermission(session, Permission.APPROVE_DENY_SUGGESTIONS)) {
            return ResponseEntity.status(403).body(Map.of("error", "Admin access required"));
        }
        try {
            return ResponseEntity.ok(suggestionService.forceReApproval(id));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
        }
    }

    @PatchMapping("/{id}/priority")
    public ResponseEntity<?> updatePriority(@PathVariable Long id,
                                            @RequestBody Map<String, String> body,
                                            HttpSession session) {
        var suggestion = suggestionService.getSuggestion(id);
        if (suggestion.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        Long userId = (Long) session.getAttribute("userId");
        boolean isAdmin = permissionService.hasPermission(session, Permission.APPROVE_DENY_SUGGESTIONS);
        boolean isAuthor = userId != null && userId.equals(suggestion.get().getAuthorId());

        if (!isAdmin && !isAuthor) {
            return ResponseEntity.status(403).body(Map.of("error", "Only admins or the suggestion author can change priority."));
        }

        String priorityStr = body.get("priority");
        Priority priority;
        try {
            priority = Priority.valueOf(priorityStr.toUpperCase());
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid priority value. Must be HIGH, MEDIUM, or LOW."));
        }

        return ResponseEntity.ok(suggestionService.updatePriority(id, priority));
    }

    @PostMapping("/{id}/vote")
    public ResponseEntity<?> vote(@PathVariable Long id, @Valid @RequestBody VoteRequest request,
                                  HttpSession session) {
        if (!permissionService.hasPermission(session, Permission.VOTE)) {
            return ResponseEntity.status(403).body(Map.of("error", "You do not have permission to vote."));
        }

        String username = (String) session.getAttribute("username");
        String voter = username != null ? username :
                (request.getVoterIdentifier() != null ? request.getVoterIdentifier() :
                        session.getId());
        try {
            Suggestion updated = voteService.vote(id, voter, request.getValue());
            return ResponseEntity.ok(Map.of(
                    "upVotes", updated.getUpVotes(),
                    "downVotes", updated.getDownVotes()
            ));
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
