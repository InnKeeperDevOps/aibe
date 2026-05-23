package com.sitemanager.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sitemanager.dto.ClarificationRequest;
import com.sitemanager.model.ExpertReviewCost;
import com.sitemanager.model.PlanTask;
import com.sitemanager.model.Suggestion;
import com.sitemanager.model.SuggestionMessage;
import com.sitemanager.model.User;
import com.sitemanager.model.enums.ExpertRole;
import com.sitemanager.model.enums.SenderType;
import com.sitemanager.model.enums.SuggestionStatus;
import com.sitemanager.model.enums.TaskStatus;
import com.sitemanager.model.enums.UserRole;
import com.sitemanager.repository.ExpertReviewCostRepository;
import com.sitemanager.repository.PlanTaskRepository;
import com.sitemanager.repository.SuggestionMessageRepository;
import com.sitemanager.repository.SuggestionRepository;
import com.sitemanager.repository.UserRepository;
import com.sitemanager.websocket.SuggestionWebSocketHandler;
import com.sitemanager.websocket.UserNotificationWebSocketHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Owns the multi-round expert review pipeline for suggestions.
 * Handles all expert review orchestration, clarification, and plan refinement.
 */
@Service
public class ExpertReviewService {

    private static final Logger log = LoggerFactory.getLogger(ExpertReviewService.class);

    static final int MAX_EXPERT_REVIEW_ROUNDS = 2;
    static final int MAX_TOTAL_EXPERT_REVIEW_ROUNDS = 3;
    static final int MIN_EXPERT_ANALYSIS_LENGTH = 50;
    static final int MAX_EXPERT_RETRIES = 2;

    private final ConcurrentHashMap<String, Integer> expertRetryCount = new ConcurrentHashMap<>();

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private final SuggestionRepository suggestionRepository;
    private final SuggestionMessageRepository messageRepository;
    private final PlanTaskRepository planTaskRepository;
    private final ClaudeService claudeService;
    private final SuggestionMessagingHelper messagingHelper;
    private final SuggestionWebSocketHandler webSocketHandler;
    private final UserNotificationWebSocketHandler userNotificationHandler;
    private final SlackNotificationService slackNotificationService;
    private final UserRepository userRepository;
    private final ExpertReviewCostRepository costRepository;
    private final CostRollupService costRollupService;
    private final SpendingLimitService spendingLimitService;
    private final SpendingAlertService spendingAlertService;

    public ExpertReviewService(SuggestionRepository suggestionRepository,
                               SuggestionMessageRepository messageRepository,
                               PlanTaskRepository planTaskRepository,
                               ClaudeService claudeService,
                               SuggestionMessagingHelper messagingHelper,
                               SuggestionWebSocketHandler webSocketHandler,
                               UserNotificationWebSocketHandler userNotificationHandler,
                               SlackNotificationService slackNotificationService,
                               UserRepository userRepository,
                               ExpertReviewCostRepository costRepository,
                               CostRollupService costRollupService,
                               SpendingLimitService spendingLimitService,
                               SpendingAlertService spendingAlertService) {
        this.suggestionRepository = suggestionRepository;
        this.messageRepository = messageRepository;
        this.planTaskRepository = planTaskRepository;
        this.claudeService = claudeService;
        this.messagingHelper = messagingHelper;
        this.webSocketHandler = webSocketHandler;
        this.userNotificationHandler = userNotificationHandler;
        this.slackNotificationService = slackNotificationService;
        this.userRepository = userRepository;
        this.costRepository = costRepository;
        this.costRollupService = costRollupService;
        this.spendingLimitService = spendingLimitService;
        this.spendingAlertService = spendingAlertService;
    }

    /**
     * Verify the budget before starting a new review. When refused, posts a
     * system message to the suggestion explaining why and pauses it in
     * place — any review already running is allowed to finish naturally
     * because this check only fires before a new CLI call is launched.
     *
     * @return {@code true} if the new review is blocked and must not start,
     *         {@code false} when the budget check passes.
     */
    private boolean isBlockedBySpendingLimit(Long suggestionId) {
        if (suggestionId == null) return false;
        SpendingLimitService.LimitCheck check;
        try {
            check = spendingLimitService.checkCanStartReview(suggestionId);
        } catch (Exception e) {
            log.warn("Spending limit check failed for suggestion {}, allowing review: {}",
                    suggestionId, e.getMessage());
            return false;
        }
        if (check == null || check.isAllowed()) return false;
        pauseForSpendingLimit(suggestionId, check.getReason());
        return true;
    }

    private void pauseForSpendingLimit(Long suggestionId, String reason) {
        Suggestion suggestion = suggestionRepository.findById(suggestionId).orElse(null);
        if (suggestion != null) {
            suggestion.setCurrentPhase("Paused — spending limit reached");
            suggestion.setLastActivityAt(Instant.now());
            suggestionRepository.save(suggestion);
            broadcastUpdate(suggestion);
        }
        addMessage(suggestionId, SenderType.SYSTEM, "System", reason);
        log.info("[BUDGET] Pausing suggestion {} — {}", suggestionId, reason);
    }

    /**
     * Persist a cost record for one expert review CLI call. Looks up the cost
     * data {@link ClaudeService} captured for {@code reviewSessionId} and
     * stores it as a single row. Best-effort: if the CLI call did not emit a
     * parseable envelope, or persistence fails, the review continues — cost
     * tracking must never break the review pipeline.
     */
    void recordReviewCost(Long suggestionId, String expertDisplayName,
                          String reviewSessionId, String operationType) {
        if (suggestionId == null || expertDisplayName == null || reviewSessionId == null) {
            return;
        }
        try {
            ClaudeCostInfo cost = claudeService.pollSessionCost(reviewSessionId);
            if (cost == null) {
                log.debug("No cost data available for review session {} (expert={})",
                        reviewSessionId, expertDisplayName);
                return;
            }
            ExpertReviewCost costRecord = new ExpertReviewCost();
            costRecord.setSuggestionId(suggestionId);
            costRecord.setExpertName(expertDisplayName);
            costRecord.setOperationType(operationType);
            costRecord.setReviewSessionId(reviewSessionId);
            costRecord.setModel(cost.getModel());
            costRecord.setInputTokens(cost.getInputTokens());
            costRecord.setOutputTokens(cost.getOutputTokens());
            costRecord.setCacheReadInputTokens(cost.getCacheReadInputTokens());
            costRecord.setCacheCreationInputTokens(cost.getCacheCreationInputTokens());
            costRecord.setCostUsd(cost.getCostUsd());
            costRecord.setDurationMs(cost.getDurationMs());
            costRepository.save(costRecord);
            log.info("[REVIEW-COST] suggestion={} expert={} cost=${} tokens={} durationMs={}",
                    suggestionId, expertDisplayName,
                    cost.getCostUsd().toPlainString(), cost.getTotalTokens(), cost.getDurationMs());
            costRollupService.broadcastUpdatedTotals(suggestionId);
            spendingAlertService.evaluateAfterCostRecorded(suggestionId);
        } catch (Exception e) {
            log.warn("Failed to persist review cost for suggestion {} expert {}: {}",
                    suggestionId, expertDisplayName, e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Public entry points
    // -------------------------------------------------------------------------

    void startExpertReviewPipeline(Long suggestionId) {
        Suggestion suggestion = suggestionRepository.findById(suggestionId).orElse(null);
        if (suggestion == null || suggestion.getStatus() != SuggestionStatus.EXPERT_REVIEW) {
            log.warn("Blocking expert review pipeline start for suggestion {} in non-EXPERT_REVIEW state",
                    suggestionId);
            return;
        }
        clearApprovalTracker(suggestion);
        broadcastExpertReviewStatus(suggestionId);
        runNextExpertReview(suggestionId);
    }

    public Map<String, Object> getExpertReviewStatus(Long suggestionId) {
        Suggestion suggestion = suggestionRepository.findById(suggestionId).orElse(null);
        if (suggestion == null || suggestion.getExpertReviewStep() == null) return null;

        int currentStep = suggestion.getExpertReviewStep();
        ExpertRole[] experts = ExpertRole.reviewOrder();
        int currentRound = suggestion.getExpertReviewRound() != null ? suggestion.getExpertReviewRound() : 1;

        List<Map<String, String>> expertList = new ArrayList<>();
        for (int i = 0; i < experts.length; i++) {
            String stepStatus;
            if (i < currentStep) stepStatus = "completed";
            else if (i == currentStep) stepStatus = "in_progress";
            else stepStatus = "pending";
            expertList.add(Map.of("name", experts[i].getDisplayName(), "status", stepStatus));
        }

        java.util.Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("currentStep", currentStep);
        result.put("totalSteps", experts.length);
        result.put("round", currentRound);
        result.put("maxRounds", MAX_EXPERT_REVIEW_ROUNDS);
        result.put("experts", expertList);
        result.put("ownerLockedSections", suggestion.getOwnerLockedSections());
        return result;
    }

    public int getExpertReviewTotalSteps() {
        return ExpertRole.reviewOrder().length;
    }

    public List<Map<String, Object>> getReviewSummary(Long suggestionId) {
        Suggestion suggestion = suggestionRepository.findById(suggestionId).orElse(null);
        if (suggestion == null) return null;

        List<Suggestion.ExpertReviewEntry> parsed = suggestion.getExpertReviewSummary();
        java.util.Map<String, Suggestion.ExpertReviewEntry> byName = new java.util.LinkedHashMap<>();
        for (Suggestion.ExpertReviewEntry e : parsed) {
            byName.put(e.getExpertName(), e);
        }

        java.util.Map<String, Boolean> ownerLockBlocked =
                detectOwnerLockBlocks(suggestion.getExpertReviewNotes());

        List<Map<String, Object>> result = new ArrayList<>();

        for (ExpertRole role : ExpertRole.reviewOrder()) {
            String displayName = role.getDisplayName();
            java.util.Map<String, Object> entry = new java.util.LinkedHashMap<>();
            if (byName.containsKey(displayName)) {
                Suggestion.ExpertReviewEntry reviewed = byName.get(displayName);
                entry.put("expertName", reviewed.getExpertName());
                entry.put("status", reviewed.getStatus());
                entry.put("keyPoint", reviewed.getKeyPoint());
                if (Boolean.TRUE.equals(ownerLockBlocked.get(displayName))) {
                    entry.put("blockedByOwnerLock", true);
                }
            } else {
                entry.put("expertName", displayName);
                entry.put("status", "PENDING");
                entry.put("keyPoint", "No review yet");
            }
            result.add(entry);
        }

        for (Suggestion.ExpertReviewEntry extra : parsed) {
            if (!java.util.Arrays.stream(ExpertRole.reviewOrder())
                    .anyMatch(r -> r.getDisplayName().equals(extra.getExpertName()))) {
                java.util.Map<String, Object> entry = new java.util.LinkedHashMap<>();
                entry.put("expertName", extra.getExpertName());
                entry.put("status", extra.getStatus());
                entry.put("keyPoint", extra.getKeyPoint());
                result.add(entry);
            }
        }

        return result;
    }

    @Transactional
    public void handleExpertReviewResponse(Long suggestionId, String response,
                                            ExpertRole expert, String reviewSessionId) {
        Suggestion suggestion = suggestionRepository.findById(suggestionId).orElse(null);
        if (suggestion == null) return;

        suggestion.setLastActivityAt(Instant.now());

        log.info("[AI-FLOW] suggestion={} expert={} review response received, responseLength={}",
                suggestionId, expert.getDisplayName(), response.length());

        try {
            String json = extractJsonBlock(response);
            if (json == null) {
                log.info("Expert {} provided no structured response, re-invoking for proper review",
                        expert.getDisplayName());
                reInvokeExpertForDetailedReview(suggestionId, expert, reviewSessionId);
                return;
            }

            JsonNode root = objectMapper.readTree(json);
            String status = root.has("status") ? root.get("status").asText() : "APPROVED";
            String analysis = root.has("analysis") ? root.get("analysis").asText() : "";
            String message = root.has("message") ? root.get("message").asText() : "";
            int currentRound = suggestion.getExpertReviewRound() != null ? suggestion.getExpertReviewRound() : 1;

            log.info("[AI-FLOW] suggestion={} expert={} verdict={} analysisLength={}",
                    suggestionId, expert.getDisplayName(), status, analysis.length());

            if (!isSubstantiveAnalysis(analysis) && "CHANGES_PROPOSED".equals(status)) {
                log.info("Expert {} provided insufficient analysis (length={}), re-invoking for detailed review",
                        expert.getDisplayName(), analysis.length());
                reInvokeExpertForDetailedReview(suggestionId, expert, reviewSessionId);
                return;
            }

            if ("NEEDS_CLARIFICATION".equals(status)) {
                List<String> questions = extractQuestions(response);
                if (questions != null && !questions.isEmpty()) {
                    try {
                        suggestion.setPendingClarificationQuestions(
                                objectMapper.writeValueAsString(questions));
                    } catch (JsonProcessingException e) {
                        log.error("Failed to serialize expert clarification questions", e);
                    }
                    suggestion.setCurrentPhase(expert.getDisplayName() + " has questions for you");
                    suggestionRepository.save(suggestion);
                    broadcastUpdate(suggestion);

                    addMessage(suggestionId, SenderType.AI, expert.getDisplayName(), message);
                    broadcastExpertClarificationQuestions(suggestionId, questions, expert);
                } else {
                    appendExpertNote(suggestion, expert, "Approved.");
                    addMessage(suggestionId, SenderType.AI, expert.getDisplayName(), "Approved.");
                    advanceExpertStep(suggestion);
                    runNextExpertReview(suggestionId);
                }
                return;
            }

            if ("CHANGES_PROPOSED".equals(status)) {
                if (expert == ExpertRole.PROJECT_OWNER) {
                    applyProjectOwnerChanges(suggestionId, suggestion, root, response, analysis, message);
                    return;
                }

                String proposedChanges = root.has("proposedChanges")
                        ? root.get("proposedChanges").asText() : "";
                ExpertRole reviewer = pickChangeReviewer(expert);

                if (isBlockedBySpendingLimit(suggestionId)) return;

                suggestion.setCurrentPhase(reviewer.getDisplayName() + " is evaluating " +
                        expert.getDisplayName() + "'s recommendations...");
                suggestionRepository.save(suggestion);
                broadcastUpdate(suggestion);

                String reviewerSessionId = claudeService.generateSessionId();
                String currentPlan = suggestion.getPlanSummary() != null ? suggestion.getPlanSummary() : "";

                claudeService.reviewExpertFeedback(
                        reviewerSessionId,
                        expert.getDisplayName(),
                        analysis,
                        proposedChanges,
                        currentPlan,
                        reviewer.getDisplayName(),
                        reviewer.getReviewPrompt(),
                        claudeService.getMainRepoDir(),
                        progress -> {},
                        currentRound,
                        suggestion.getOwnerLockedSections()
                ).thenAccept(reviewerResponse -> {
                    recordReviewCost(suggestionId, reviewer.getDisplayName(), reviewerSessionId,
                            "review-feedback:" + reviewer.getDisplayName() + "<-" + expert.getDisplayName());
                    handleReviewerResponse(suggestionId, reviewerResponse, expert,
                            response, analysis, message);
                });
                return;
            }

            updateApprovalTracker(suggestion, expert, "APPROVED", currentRound);
            appendExpertNote(suggestion, expert, "Approved.");
            addMessage(suggestionId, SenderType.AI, expert.getDisplayName(), "Approved.");
            advanceExpertStep(suggestion);
            runNextExpertReview(suggestionId);

        } catch (Exception e) {
            log.error("Failed to handle expert review response for suggestion {}: {}",
                    suggestionId, e.getMessage(), e);
            log.info("Re-invoking {} after error to ensure participation", expert.getDisplayName());
            reInvokeExpertForDetailedReview(suggestionId, expert, reviewSessionId);
        }
    }

    @Transactional
    public void handleReviewerResponse(Long suggestionId, String reviewerResponse,
                                        ExpertRole expert, String originalExpertResponse,
                                        String expertAnalysis, String expertMessage) {
        Suggestion suggestion = suggestionRepository.findById(suggestionId).orElse(null);
        if (suggestion == null) return;

        int currentRound = suggestion.getExpertReviewRound() != null ? suggestion.getExpertReviewRound() : 1;
        boolean applyChanges = false;
        String reviewerNotes = "";

        try {
            String json = extractJsonBlock(reviewerResponse);
            if (json != null) {
                JsonNode root = objectMapper.readTree(json);
                applyChanges = root.has("apply") && root.get("apply").asBoolean();
                reviewerNotes = root.has("notes") ? root.get("notes").asText() : "";
            }
        } catch (Exception e) {
            log.warn("Failed to parse reviewer response: {}", e.getMessage());
        }

        if (applyChanges && isPlanLocked(suggestion.getStatus())) {
            log.warn("Blocking expert plan changes for suggestion {} — plan is locked in {} state",
                    suggestionId, suggestion.getStatus());
            applyChanges = false;
        }

        if (applyChanges) {
            boolean allChangesBlocked = false;
            String ownerLockNote = "";

            try {
                String json = extractJsonBlock(originalExpertResponse);
                if (json != null) {
                    JsonNode root = objectMapper.readTree(json);
                    List<Integer> lockedSections = suggestion.getOwnerLockedSections();
                    boolean hasTaskChanges = root.has("revisedTasks") && root.get("revisedTasks").isArray();
                    boolean hasPlanChanges = root.has("revisedPlan") || root.has("revisedPlanDisplaySummary");

                    if (!lockedSections.isEmpty() && hasTaskChanges) {
                        OwnerLockResult lockResult = enforceOwnerLockOnTasks(
                                suggestionId, root.get("revisedTasks"), lockedSections);

                        if (lockResult.allTaskChangesBlocked()) {
                            ownerLockNote = lockResult.blockedNote();
                            if (!hasPlanChanges) {
                                allChangesBlocked = true;
                            } else {
                                if (root.has("revisedPlan")) {
                                    suggestion.setPlanSummary(root.get("revisedPlan").asText());
                                }
                                if (root.has("revisedPlanDisplaySummary")) {
                                    suggestion.setPlanDisplaySummary(
                                            root.get("revisedPlanDisplaySummary").asText());
                                }
                            }
                        } else {
                            if (root.has("revisedPlan")) {
                                suggestion.setPlanSummary(root.get("revisedPlan").asText());
                            }
                            if (root.has("revisedPlanDisplaySummary")) {
                                suggestion.setPlanDisplaySummary(
                                        root.get("revisedPlanDisplaySummary").asText());
                            }
                            savePlanTasksFromNode(suggestionId, lockResult.filteredTasks());
                            if (lockResult.anyBlocked()) {
                                ownerLockNote = lockResult.blockedNote();
                            }
                        }
                    } else {
                        if (root.has("revisedPlan")) {
                            suggestion.setPlanSummary(root.get("revisedPlan").asText());
                        }
                        if (root.has("revisedPlanDisplaySummary")) {
                            suggestion.setPlanDisplaySummary(
                                    root.get("revisedPlanDisplaySummary").asText());
                        }
                        if (hasTaskChanges) {
                            savePlanTasksFromNode(suggestionId, root.get("revisedTasks"));
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to apply expert changes: {}", e.getMessage());
            }

            if (allChangesBlocked) {
                appendExpertNote(suggestion, expert,
                        "Proposed changes — not applied. All task changes were blocked by owner-lock constraints. "
                        + ownerLockNote + "\nReviewer: " + reviewerNotes);
                addMessage(suggestionId, SenderType.AI, expert.getDisplayName(),
                        expertMessage + "\n\n*Recommendations were noted but owner-protected tasks prevented changes from being applied.*");
                advanceExpertStep(suggestion);
                broadcastTasks(suggestionId);
                runNextExpertReview(suggestionId);
            } else {
                updateApprovalTracker(suggestion, expert, "CHANGES_APPLIED", currentRound);
                String noteExtra = ownerLockNote.isBlank() ? "" : "\nOwner lock: " + ownerLockNote;
                appendExpertNote(suggestion, expert,
                        "Proposed changes — ACCEPTED. " + expertAnalysis +
                        "\nReviewer: " + reviewerNotes + noteExtra);
                addMessage(suggestionId, SenderType.AI, expert.getDisplayName(),
                        expertMessage + "\n\n*Changes have been applied to the plan.*");

                suggestion.setExpertReviewPlanChanged(true);
                String changedDomain = expert.domain().name();
                String existing = suggestion.getExpertReviewChangedDomains();
                if (existing == null || existing.isBlank()) {
                    suggestion.setExpertReviewChangedDomains(changedDomain);
                } else if (!existing.contains(changedDomain)) {
                    suggestion.setExpertReviewChangedDomains(existing + "," + changedDomain);
                }
                suggestionRepository.save(suggestion);

                addMessage(suggestionId, SenderType.SYSTEM, "System",
                        "The plan was updated. Remaining experts will continue reviewing before a new round begins.");
                advanceExpertStep(suggestion);
                broadcastTasks(suggestionId);
                broadcastExpertReviewStatus(suggestionId);
                runNextExpertReview(suggestionId);
            }
        } else {
            updateApprovalTracker(suggestion, expert, "APPROVED", currentRound);
            appendExpertNote(suggestion, expert,
                    "Proposed changes — not applied. " + expertAnalysis +
                    "\nReviewer: " + reviewerNotes);
            addMessage(suggestionId, SenderType.AI, expert.getDisplayName(),
                    expertMessage + "\n\n*Recommendations were noted but the plan remains unchanged.*");

            advanceExpertStep(suggestion);
            broadcastTasks(suggestionId);
            runNextExpertReview(suggestionId);
        }
    }

    @Transactional
    public void handleExpertClarificationAnswers(Long suggestionId, String senderName,
                                                   List<ClarificationRequest.ClarificationAnswer> answers) {
        Suggestion suggestion = suggestionRepository.findById(suggestionId)
                .orElseThrow(() -> new IllegalArgumentException("Suggestion not found"));

        if (suggestion.getStatus() != SuggestionStatus.EXPERT_REVIEW) {
            log.warn("Rejecting expert clarification for suggestion {} in state {}",
                    suggestionId, suggestion.getStatus());
            throw new IllegalStateException(
                    "Expert reviews cannot be submitted when the suggestion is in " +
                    suggestion.getStatus() + " state.");
        }

        suggestion.setLastActivityAt(Instant.now());
        suggestion.setPendingClarificationQuestions(null);
        suggestionRepository.save(suggestion);

        int step = suggestion.getExpertReviewStep() != null ? suggestion.getExpertReviewStep() : 0;
        ExpertRole expert = ExpertRole.fromStep(step);

        if (expert == null) {
            int totalRounds = suggestion.getTotalExpertReviewRounds() != null
                    ? suggestion.getTotalExpertReviewRounds() : 0;

            StringBuilder formattedMsg = new StringBuilder();
            for (ClarificationRequest.ClarificationAnswer qa : answers) {
                formattedMsg.append("**Q: ").append(qa.getQuestion()).append("**\n");
                formattedMsg.append("A: ").append(qa.getAnswer()).append("\n\n");
            }
            addMessage(suggestionId, SenderType.USER, senderName, formattedMsg.toString().trim());

            if (totalRounds >= MAX_TOTAL_EXPERT_REVIEW_ROUNDS) {
                log.warn("Suggestion {} hit hard cap of {} total review rounds after user guidance, force-finalizing",
                        suggestionId, MAX_TOTAL_EXPERT_REVIEW_ROUNDS);
                addMessage(suggestionId, SenderType.SYSTEM, "System",
                        "Thank you for the guidance. The plan has been through extensive review (" +
                        totalRounds + " total rounds) and will be finalized as-is.");

                suggestion.setStatus(SuggestionStatus.PLANNED);
                suggestion.setExpertReviewStep(null);
                suggestion.setExpertReviewRound(null);
                suggestion.setExpertReviewPlanChanged(null);
                suggestion.setTotalExpertReviewRounds(null);
                suggestion.setCurrentPhase("Plan ready — waiting for approval");
                suggestionRepository.save(suggestion);
                broadcastUpdate(suggestion);
                broadcastExpertReviewStatus(suggestionId);
                notifyAdminsApprovalNeeded(suggestion);
                return;
            }

            String userGuidance = "User guidance after review rounds:\n" +
                    formattedMsg.toString().trim();
            String existingNotes = suggestion.getExpertReviewNotes();
            suggestion.setExpertReviewNotes(
                    (existingNotes != null ? existingNotes + "\n\n" : "") + userGuidance);

            java.util.List<ExpertRole> expertsToRerun = java.util.Arrays.stream(ExpertRole.reviewOrder())
                    .filter(e -> !"APPROVED".equals(getApprovalStatus(suggestion, e)))
                    .toList();

            if (expertsToRerun.isEmpty()) {
                log.info("Suggestion {} — all experts already approved, skipping re-review after user guidance",
                        suggestionId);
                addMessage(suggestionId, SenderType.SYSTEM, "System",
                        "All experts have already approved the plan. Proceeding to final approval.");
                suggestion.setStatus(SuggestionStatus.PLANNED);
                suggestion.setExpertReviewStep(null);
                suggestion.setExpertReviewRound(null);
                suggestion.setExpertReviewPlanChanged(null);
                suggestion.setTotalExpertReviewRounds(null);
                suggestion.setExpertReviewChangedDomains(null);
                suggestion.setCurrentPhase("Plan ready — waiting for approval");
                suggestionRepository.save(suggestion);
                broadcastUpdate(suggestion);
                broadcastExpertReviewStatus(suggestionId);
                notifyAdminsApprovalNeeded(suggestion);
                return;
            }

            suggestion.setExpertReviewRound(1);
            suggestion.setTotalExpertReviewRounds(totalRounds + 1);
            suggestion.setExpertReviewPlanChanged(false);
            suggestion.setCurrentPhase("Restarting expert reviews with your guidance...");
            suggestionRepository.save(suggestion);
            broadcastUpdate(suggestion);

            addMessage(suggestionId, SenderType.SYSTEM, "System",
                    "Thank you for the guidance. Restarting expert reviews with your direction in mind.");
            broadcastExpertReviewStatus(suggestionId);
            runTargetedExpertRereviews(suggestionId, expertsToRerun, 1);
            return;
        }

        StringBuilder formattedMessage = new StringBuilder();
        for (ClarificationRequest.ClarificationAnswer qa : answers) {
            formattedMessage.append("**Q: ").append(qa.getQuestion()).append("**\n");
            formattedMessage.append("A: ").append(qa.getAnswer()).append("\n\n");
        }
        addMessage(suggestionId, SenderType.USER, senderName, formattedMessage.toString().trim());

        StringBuilder prompt = new StringBuilder();
        prompt.append("The user has provided answers to your questions. Please continue your review as ")
              .append(expert.getDisplayName()).append(".\n\n");
        for (ClarificationRequest.ClarificationAnswer qa : answers) {
            prompt.append("Question: ").append(qa.getQuestion()).append("\n");
            prompt.append("Answer: ").append(qa.getAnswer()).append("\n\n");
        }
        prompt.append("Based on these answers, complete your review of the plan.\n\n");
        prompt.append("Respond in this JSON format:\n");
        prompt.append("If the plan looks good: {\"status\": \"APPROVED\", \"analysis\": \"...\", \"message\": \"...\"}\n");
        prompt.append("If you recommend changes: {\"status\": \"CHANGES_PROPOSED\", \"analysis\": \"...\", ");
        prompt.append("\"proposedChanges\": \"...\", \"revisedPlan\": \"...\", ");
        prompt.append("\"revisedTasks\": [{\"title\": \"...\", \"description\": \"...\", \"estimatedMinutes\": N}, ...], ");
        prompt.append("\"message\": \"...\"}\n");
        prompt.append("If you still need clarification: {\"status\": \"NEEDS_CLARIFICATION\", \"analysis\": \"...\", ");
        prompt.append("\"questions\": [...], \"message\": \"...\"}\n\n");
        prompt.append("COMMUNICATION RULES: All messages MUST be plain, non-technical language. NEVER mention technologies or implementation details.");

        if (isBlockedBySpendingLimit(suggestionId)) return;

        suggestion.setCurrentPhase(expert.getDisplayName() + " is reviewing your answers...");
        suggestionRepository.save(suggestion);
        broadcastUpdate(suggestion);

        String reviewSessionId = claudeService.generateSessionId();
        claudeService.continueConversation(
                reviewSessionId,
                prompt.toString(),
                null,
                claudeService.getMainRepoDir(),
                progress -> webSocketHandler.sendToSuggestion(suggestionId,
                        "{\"type\":\"progress\",\"content\":\"" +
                                escapeJson(progress) + "\"}")
        ).thenAccept(response -> {
            recordReviewCost(suggestionId, expert.getDisplayName(), reviewSessionId,
                    "expert-review:" + expert.getDisplayName() + ":clarification-answer");
            handleExpertReviewResponse(suggestionId, response, expert, reviewSessionId);
        });
    }

    // -------------------------------------------------------------------------
    // Private orchestration helpers
    // -------------------------------------------------------------------------

    private void runNextExpertReview(Long suggestionId) {
        Suggestion suggestion = suggestionRepository.findById(suggestionId).orElse(null);
        if (suggestion == null) return;

        if (suggestion.getStatus() != SuggestionStatus.EXPERT_REVIEW) {
            log.warn("Aborting expert review step for suggestion {} — status is {}",
                    suggestionId, suggestion.getStatus());
            return;
        }

        int step = suggestion.getExpertReviewStep() != null ? suggestion.getExpertReviewStep() : 0;
        ExpertRole[] experts = ExpertRole.reviewOrder();

        if (step >= experts.length) {
            boolean planChanged = Boolean.TRUE.equals(suggestion.getExpertReviewPlanChanged());
            int currentRound = suggestion.getExpertReviewRound() != null ? suggestion.getExpertReviewRound() : 1;
            int totalRounds = suggestion.getTotalExpertReviewRounds() != null
                    ? suggestion.getTotalExpertReviewRounds() : currentRound;

            if (planChanged && currentRound < MAX_EXPERT_REVIEW_ROUNDS
                    && totalRounds < MAX_TOTAL_EXPERT_REVIEW_ROUNDS) {
                java.util.Set<ExpertRole.Domain> changedDomains =
                        parseChangedDomains(suggestion.getExpertReviewChangedDomains());
                java.util.Set<ExpertRole.Domain> affectedDomains = new java.util.HashSet<>();
                for (ExpertRole.Domain d : changedDomains) {
                    affectedDomains.addAll(ExpertRole.affectedDomains(d));
                }

                java.util.List<ExpertRole> expertsToRerun = java.util.Arrays.stream(ExpertRole.reviewOrder())
                        .filter(e -> affectedDomains.contains(e.domain()))
                        .filter(e -> {
                            String approvalStatus = getApprovalStatus(suggestion, e);
                            boolean alreadyApproved = "APPROVED".equals(approvalStatus);
                            boolean ownDomainChanged = changedDomains.contains(e.domain());
                            return !alreadyApproved || ownDomainChanged;
                        })
                        .toList();

                if (expertsToRerun.isEmpty()) {
                    log.info("Suggestion {} — all affected reviewers already approved, skipping re-review round",
                            suggestionId);
                    addMessage(suggestionId, SenderType.SYSTEM, "System",
                            "All affected reviewers already approved — proceeding to final approval.");
                    suggestion.setStatus(SuggestionStatus.PLANNED);
                    suggestion.setExpertReviewStep(null);
                    suggestion.setExpertReviewRound(null);
                    suggestion.setExpertReviewPlanChanged(null);
                    suggestion.setTotalExpertReviewRounds(null);
                    suggestion.setExpertReviewChangedDomains(null);
                    suggestion.setCurrentPhase("Plan ready — waiting for approval");
                    suggestionRepository.save(suggestion);
                    broadcastUpdate(suggestion);
                    broadcastExpertReviewStatus(suggestionId);
                    notifyAdminsApprovalNeeded(suggestion);
                    return;
                }

                int nextRound = currentRound + 1;
                suggestion.setExpertReviewRound(nextRound);
                suggestion.setTotalExpertReviewRounds(totalRounds + 1);
                suggestion.setExpertReviewNotes(null);
                suggestion.setExpertReviewPlanChanged(false);
                suggestion.setExpertReviewChangedDomains(null);
                suggestionRepository.save(suggestion);

                String expertNames = expertsToRerun.stream()
                        .map(ExpertRole::getDisplayName)
                        .collect(java.util.stream.Collectors.joining(", "));
                addMessage(suggestionId, SenderType.SYSTEM, "System",
                        "The plan was updated — targeted re-review by " + expertNames +
                        " (round " + nextRound + ").");
                broadcastTasks(suggestionId);
                broadcastExpertReviewStatus(suggestionId);
                runTargetedExpertRereviews(suggestionId, expertsToRerun, nextRound);
                return;
            }

            if (planChanged && totalRounds >= MAX_TOTAL_EXPERT_REVIEW_ROUNDS) {
                log.warn("Suggestion {} hit hard cap of {} total expert review rounds, force-finalizing",
                        suggestionId, MAX_TOTAL_EXPERT_REVIEW_ROUNDS);
                addMessage(suggestionId, SenderType.SYSTEM, "System",
                        "Expert reviews have completed after " + totalRounds +
                        " total rounds of refinement. Finalizing the plan as-is.");
            } else if (planChanged) {
                log.info("Suggestion {} reached max expert review rounds ({}), force-finalizing",
                        suggestionId, MAX_EXPERT_REVIEW_ROUNDS);
                addMessage(suggestionId, SenderType.SYSTEM, "System",
                        "Expert reviews have completed after " + currentRound +
                        " rounds. Finalizing the plan.");
            }

            addMessage(suggestionId, SenderType.SYSTEM, "System",
                    "All expert reviews are complete. The plan is ready for approval.");

            suggestion.setStatus(SuggestionStatus.PLANNED);
            suggestion.setExpertReviewStep(null);
            suggestion.setExpertReviewRound(null);
            suggestion.setExpertReviewPlanChanged(null);
            suggestion.setTotalExpertReviewRounds(null);
            suggestion.setExpertReviewChangedDomains(null);
            suggestion.setCurrentPhase("Plan ready — waiting for approval");
            suggestionRepository.save(suggestion);
            broadcastUpdate(suggestion);
            broadcastExpertReviewStatus(suggestionId);
            notifyAdminsApprovalNeeded(suggestion);
            return;
        }

        int[] batchInfo = ExpertRole.batchForStep(step);
        if (batchInfo == null) {
            runSingleExpertReview(suggestionId, suggestion, experts[step]);
            return;
        }

        int batchIndex = batchInfo[0];
        int positionInBatch = batchInfo[1];

        if (positionInBatch == 0) {
            runExpertBatch(suggestionId, batchIndex);
        } else {
            runSingleExpertReview(suggestionId, suggestion, experts[step]);
        }
    }

    private void runExpertBatch(Long suggestionId, int batchIndex) {
        if (isBlockedBySpendingLimit(suggestionId)) return;

        Suggestion suggestion = suggestionRepository.findById(suggestionId).orElse(null);
        if (suggestion == null) return;

        ExpertRole[][] batches = ExpertRole.reviewBatches();
        if (batchIndex >= batches.length) return;

        ExpertRole[] batch = batches[batchIndex];
        long batchStart = System.currentTimeMillis();
        log.info("[EXPERT-BATCH] Starting batch {} ({} experts: {}) for suggestion {}",
                batchIndex + 1, batch.length,
                String.join(", ", java.util.Arrays.stream(batch)
                        .map(ExpertRole::getDisplayName).toArray(String[]::new)),
                suggestionId);

        String expertNames = String.join(", ",
                java.util.Arrays.stream(batch)
                        .map(ExpertRole::getDisplayName).toArray(String[]::new));
        suggestion.setCurrentPhase(expertNames + " are reviewing the plan...");
        suggestionRepository.save(suggestion);
        broadcastUpdate(suggestion);
        broadcastExpertReviewStatus(suggestionId);

        String plan = suggestion.getPlanSummary() != null
                ? suggestion.getPlanSummary() : suggestion.getDescription();
        String tasksJson = buildTasksJsonForExecution(suggestionId);
        String previousNotes = suggestion.getExpertReviewNotes();

        int currentRound = suggestion.getExpertReviewRound() != null ? suggestion.getExpertReviewRound() : 1;
        List<Integer> ownerLockedSections = suggestion.getOwnerLockedSections();
        List<CompletableFuture<ExpertBatchResult>> futures = new ArrayList<>();

        for (ExpertRole expert : batch) {
            String reviewSessionId = claudeService.generateSessionId();
            CompletableFuture<ExpertBatchResult> future = claudeService.expertReview(
                    reviewSessionId,
                    expert.getDisplayName(),
                    expert.getReviewPrompt(),
                    suggestion.getTitle(),
                    suggestion.getDescription(),
                    plan,
                    tasksJson,
                    previousNotes,
                    claudeService.getMainRepoDir(),
                    progress -> webSocketHandler.sendToSuggestion(suggestionId,
                            "{\"type\":\"progress\",\"content\":\"" +
                                    escapeJson(progress) + "\"}"),
                    currentRound,
                    ownerLockedSections
            ).thenApply(response -> new ExpertBatchResult(expert, reviewSessionId, response));
            futures.add(future);
        }

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenRun(() -> {
                    long batchElapsed = System.currentTimeMillis() - batchStart;
                    log.info("[EXPERT-BATCH] Batch {} completed in {}ms for suggestion {}",
                            batchIndex + 1, batchElapsed, suggestionId);

                    for (CompletableFuture<ExpertBatchResult> future : futures) {
                        try {
                            ExpertBatchResult result = future.join();
                            recordReviewCost(suggestionId, result.expert.getDisplayName(),
                                    result.sessionId, "expert-review:" + result.expert.getDisplayName());
                            handleExpertReviewResponse(suggestionId, result.response,
                                    result.expert, result.sessionId);

                            Suggestion refreshed = suggestionRepository.findById(suggestionId).orElse(null);
                            if (refreshed == null) return;
                            if (refreshed.getPendingClarificationQuestions() != null) {
                                log.info("[EXPERT-BATCH] Batch {} paused: {} needs clarification for suggestion {}",
                                        batchIndex + 1, result.expert.getDisplayName(), suggestionId);
                                return;
                            }
                        } catch (Exception e) {
                            log.error("[EXPERT-BATCH] Error processing result for suggestion {}: {}",
                                    suggestionId, e.getMessage(), e);
                        }
                    }
                });
    }

    private void runSingleExpertReview(Long suggestionId, Suggestion suggestion, ExpertRole expert) {
        if (isBlockedBySpendingLimit(suggestionId)) return;

        suggestion.setCurrentPhase(expert.getDisplayName() + " is reviewing the plan...");
        suggestionRepository.save(suggestion);
        broadcastUpdate(suggestion);
        broadcastExpertReviewStatus(suggestionId);

        String plan = suggestion.getPlanSummary() != null
                ? suggestion.getPlanSummary() : suggestion.getDescription();
        String tasksJson = buildTasksJsonForExecution(suggestionId);
        String previousNotes = suggestion.getExpertReviewNotes();

        int currentRound = suggestion.getExpertReviewRound() != null ? suggestion.getExpertReviewRound() : 1;
        String reviewSessionId = claudeService.generateSessionId();
        claudeService.expertReview(
                reviewSessionId,
                expert.getDisplayName(),
                expert.getReviewPrompt(),
                suggestion.getTitle(),
                suggestion.getDescription(),
                plan,
                tasksJson,
                previousNotes,
                claudeService.getMainRepoDir(),
                progress -> webSocketHandler.sendToSuggestion(suggestionId,
                        "{\"type\":\"progress\",\"content\":\"" +
                                escapeJson(progress) + "\"}"),
                currentRound,
                suggestion.getOwnerLockedSections()
        ).thenAccept(response -> {
            recordReviewCost(suggestionId, expert.getDisplayName(), reviewSessionId,
                    "expert-review:" + expert.getDisplayName());
            handleExpertReviewResponse(suggestionId, response, expert, reviewSessionId);
        });
    }

    @Transactional
    private void applyProjectOwnerChanges(Long suggestionId, Suggestion suggestion,
                                           JsonNode root, String response,
                                           String analysis, String message) {
        try {
            if (root.has("revisedPlan")) {
                suggestion.setPlanSummary(root.get("revisedPlan").asText());
            }
            if (root.has("revisedPlanDisplaySummary")) {
                suggestion.setPlanDisplaySummary(root.get("revisedPlanDisplaySummary").asText());
            }
            if (root.has("revisedTasks") && root.get("revisedTasks").isArray()) {
                savePlanTasksFromNode(suggestionId, root.get("revisedTasks"));
            }

            if (root.has("lockedTaskIndices") && root.get("lockedTaskIndices").isArray()) {
                List<Integer> lockedIndices = new ArrayList<>();
                for (JsonNode idx : root.get("lockedTaskIndices")) {
                    lockedIndices.add(idx.asInt());
                }
                suggestion.setOwnerLockedSections(lockedIndices);
            }

            suggestion.setExpertReviewPlanChanged(true);
            String changedDomain = ExpertRole.PROJECT_OWNER.domain().name();
            String existingDomains = suggestion.getExpertReviewChangedDomains();
            if (existingDomains == null || existingDomains.isBlank()) {
                suggestion.setExpertReviewChangedDomains(changedDomain);
            } else if (!existingDomains.contains(changedDomain)) {
                suggestion.setExpertReviewChangedDomains(existingDomains + "," + changedDomain);
            }

            appendExpertNote(suggestion, ExpertRole.PROJECT_OWNER, "Changes applied. " + analysis);
            addMessage(suggestionId, SenderType.AI, ExpertRole.PROJECT_OWNER.getDisplayName(),
                    message + "\n\n*Changes have been applied to the plan.*");

            broadcastExpertReviewStatus(suggestionId);
            advanceExpertStep(suggestion);
            runNextExpertReview(suggestionId);

        } catch (Exception e) {
            log.error("Failed to apply Project Owner changes for suggestion {}: {}",
                    suggestionId, e.getMessage(), e);
            appendExpertNote(suggestion, ExpertRole.PROJECT_OWNER, "Approved.");
            addMessage(suggestionId, SenderType.AI, ExpertRole.PROJECT_OWNER.getDisplayName(), "Approved.");
            advanceExpertStep(suggestion);
            runNextExpertReview(suggestionId);
        }
    }

    private void advanceExpertStep(Suggestion suggestion) {
        int current = suggestion.getExpertReviewStep() != null ? suggestion.getExpertReviewStep() : 0;
        ExpertRole expert = ExpertRole.fromStep(current);
        if (expert != null) {
            expertRetryCount.remove(suggestion.getId() + "-" + expert.name());
        }
        suggestion.setExpertReviewStep(current + 1);
        suggestionRepository.save(suggestion);
        broadcastExpertReviewStatus(suggestion.getId());
    }

    private void appendExpertNote(Suggestion suggestion, ExpertRole expert, String note) {
        String existing = suggestion.getExpertReviewNotes();
        String entry = "**" + expert.getDisplayName() + "**: " + note;
        if (existing == null || existing.isBlank()) {
            suggestion.setExpertReviewNotes(entry);
        } else {
            suggestion.setExpertReviewNotes(existing + "\n\n" + entry);
        }
        suggestionRepository.save(suggestion);
        messagingHelper.broadcastExpertNote(suggestion.getId(), expert.getDisplayName(), note);
    }

    private ExpertRole pickChangeReviewer(ExpertRole proposingExpert) {
        return switch (proposingExpert) {
            case FRONTEND_ENGINEER, UX_EXPERT, QA_ENGINEER -> ExpertRole.PRODUCT_MANAGER;
            default -> ExpertRole.SOFTWARE_ARCHITECT;
        };
    }

    private java.util.Set<ExpertRole.Domain> parseChangedDomains(String domainsStr) {
        java.util.Set<ExpertRole.Domain> domains = new java.util.HashSet<>();
        if (domainsStr == null || domainsStr.isBlank()) return domains;
        for (String name : domainsStr.split(",")) {
            try {
                domains.add(ExpertRole.Domain.valueOf(name.trim()));
            } catch (IllegalArgumentException e) {
                log.warn("Unknown domain in changed domains: {}", name);
            }
        }
        return domains;
    }

    private void runTargetedExpertRereviews(Long suggestionId,
                                             java.util.List<ExpertRole> experts, int round) {
        if (experts.isEmpty()) {
            Suggestion s = suggestionRepository.findById(suggestionId).orElse(null);
            if (s == null) return;
            s.setExpertReviewStep(ExpertRole.reviewOrder().length);
            suggestionRepository.save(s);
            runNextExpertReview(suggestionId);
            return;
        }

        if (isBlockedBySpendingLimit(suggestionId)) return;

        ExpertRole expert = experts.get(0);
        java.util.List<ExpertRole> remaining = experts.subList(1, experts.size());

        Suggestion suggestion = suggestionRepository.findById(suggestionId).orElse(null);
        if (suggestion == null) return;

        suggestion.setCurrentPhase(expert.getDisplayName() + " is re-reviewing the plan (round " + round + ")...");
        suggestionRepository.save(suggestion);
        broadcastUpdate(suggestion);

        String plan = suggestion.getPlanSummary() != null
                ? suggestion.getPlanSummary() : suggestion.getDescription();
        String tasksJson = buildTasksJsonForExecution(suggestionId);
        String previousNotes = suggestion.getExpertReviewNotes();

        String reviewSessionId = claudeService.generateSessionId();
        claudeService.expertReview(
                reviewSessionId,
                expert.getDisplayName(),
                expert.getReviewPrompt(),
                suggestion.getTitle(),
                suggestion.getDescription(),
                plan,
                tasksJson,
                previousNotes,
                claudeService.getMainRepoDir(),
                progress -> webSocketHandler.sendToSuggestion(suggestionId,
                        "{\"type\":\"progress\",\"content\":\"" +
                                escapeJson(progress) + "\"}"),
                round,
                suggestion.getOwnerLockedSections()
        ).thenAccept(response -> {
            recordReviewCost(suggestionId, expert.getDisplayName(), reviewSessionId,
                    "expert-review:" + expert.getDisplayName());
            handleExpertReviewResponse(suggestionId, response, expert, reviewSessionId);

            Suggestion refreshed = suggestionRepository.findById(suggestionId).orElse(null);
            if (refreshed != null && refreshed.getPendingClarificationQuestions() == null) {
                if (!remaining.isEmpty()) {
                    runTargetedExpertRereviews(suggestionId, remaining, round);
                } else {
                    refreshed.setExpertReviewStep(ExpertRole.reviewOrder().length);
                    suggestionRepository.save(refreshed);
                    runNextExpertReview(suggestionId);
                }
            }
        });
    }

    private void reInvokeExpertForDetailedReview(Long suggestionId, ExpertRole expert,
                                                   String originalSessionId) {
        if (isBlockedBySpendingLimit(suggestionId)) return;

        Suggestion suggestion = suggestionRepository.findById(suggestionId).orElse(null);
        if (suggestion == null) return;

        String retryKey = suggestionId + "-" + expert.name();
        int retries = expertRetryCount.getOrDefault(retryKey, 0);
        if (retries >= MAX_EXPERT_RETRIES) {
            log.warn("Expert {} exceeded max retries for suggestion {}, accepting current response",
                    expert.getDisplayName(), suggestionId);
            expertRetryCount.remove(retryKey);
            appendExpertNote(suggestion, expert, "Review completed (response accepted after retries).");
            advanceExpertStep(suggestion);
            runNextExpertReview(suggestionId);
            return;
        }
        expertRetryCount.put(retryKey, retries + 1);

        suggestion.setCurrentPhase(expert.getDisplayName() + " is providing more detail...");
        suggestionRepository.save(suggestion);
        broadcastUpdate(suggestion);

        String plan = suggestion.getPlanSummary() != null
                ? suggestion.getPlanSummary() : suggestion.getDescription();
        String tasksJson = buildTasksJsonForExecution(suggestionId);
        String previousNotes = suggestion.getExpertReviewNotes();

        String retrySessionId = claudeService.generateSessionId();
        String reinforcedPrompt = expert.getReviewPrompt() +
                "\n\nIMPORTANT: Your previous response did not include enough detail. " +
                "You MUST provide a thorough, substantive analysis (at least 2-3 sentences) covering specific aspects " +
                "of this plan from your expertise as a " + expert.getDisplayName() + ". " +
                "Explain what you evaluated, what you found, and why. Do NOT give a brief or generic response.";

        int currentRound = suggestion.getExpertReviewRound() != null ? suggestion.getExpertReviewRound() : 1;
        claudeService.expertReview(
                retrySessionId,
                expert.getDisplayName(),
                reinforcedPrompt,
                suggestion.getTitle(),
                suggestion.getDescription(),
                plan,
                tasksJson,
                previousNotes,
                claudeService.getMainRepoDir(),
                progress -> webSocketHandler.sendToSuggestion(suggestionId,
                        "{\"type\":\"progress\",\"content\":\"" +
                                escapeJson(progress) + "\"}"),
                currentRound,
                suggestion.getOwnerLockedSections()
        ).thenAccept(response -> {
            recordReviewCost(suggestionId, expert.getDisplayName(), retrySessionId,
                    "expert-review:" + expert.getDisplayName());
            handleExpertReviewResponse(suggestionId, response, expert, retrySessionId);
        });
    }

    private OwnerLockResult enforceOwnerLockOnTasks(Long suggestionId,
                                                     JsonNode proposedTasksNode,
                                                     List<Integer> lockedIndices) {
        List<PlanTask> currentTasks =
                planTaskRepository.findBySuggestionIdOrderByTaskOrder(suggestionId);
        List<String> removalReasons = new ArrayList<>();

        for (int lockedIdx : lockedIndices) {
            if (lockedIdx >= proposedTasksNode.size()) {
                removalReasons.add("task " + (lockedIdx + 1) + " cannot be removed");
            }
        }

        if (!removalReasons.isEmpty()) {
            String note = "Owner-protected tasks were removed: " + String.join(", ", removalReasons);
            return new OwnerLockResult(true, true, note, proposedTasksNode);
        }

        ArrayNode filteredTasks = objectMapper.createArrayNode();
        List<String> scopeBlockReasons = new ArrayList<>();

        for (int i = 0; i < proposedTasksNode.size(); i++) {
            JsonNode proposedTask = proposedTasksNode.get(i);

            if (lockedIndices.contains(i) && i < currentTasks.size()) {
                PlanTask currentTask = currentTasks.get(i);
                String proposedDisplayTitle = proposedTask.has("displayTitle")
                        ? proposedTask.get("displayTitle").asText("") : "";
                String proposedDisplayDesc = proposedTask.has("displayDescription")
                        ? proposedTask.get("displayDescription").asText("") : "";
                String currentDisplayTitle = currentTask.getDisplayTitle() != null
                        ? currentTask.getDisplayTitle() : "";
                String currentDisplayDesc = currentTask.getDisplayDescription() != null
                        ? currentTask.getDisplayDescription() : "";

                boolean titleChanged = !currentDisplayTitle.equals(proposedDisplayTitle);
                boolean descChanged = !currentDisplayDesc.equals(proposedDisplayDesc);

                if (titleChanged || descChanged) {
                    ObjectNode fixedTask = objectMapper.createObjectNode();
                    proposedTask.fields().forEachRemaining(e -> fixedTask.set(e.getKey(), e.getValue()));
                    fixedTask.put("displayTitle", currentDisplayTitle);
                    fixedTask.put("displayDescription", currentDisplayDesc);
                    filteredTasks.add(fixedTask);

                    if (titleChanged) scopeBlockReasons.add("task " + (i + 1) + " title is owner-protected");
                    if (descChanged) scopeBlockReasons.add("task " + (i + 1) + " description is owner-protected");
                } else {
                    filteredTasks.add(proposedTask);
                }
            } else {
                filteredTasks.add(proposedTask);
            }
        }

        if (scopeBlockReasons.isEmpty()) {
            return new OwnerLockResult(false, false, "", proposedTasksNode);
        }

        String note = "Some changes to owner-protected tasks were blocked: "
                + String.join(", ", scopeBlockReasons);
        return new OwnerLockResult(true, false, note, filteredTasks);
    }

    private java.util.Map<String, Boolean> detectOwnerLockBlocks(String notes) {
        java.util.Map<String, Boolean> blocked = new java.util.LinkedHashMap<>();
        if (notes == null || notes.isBlank()) return blocked;
        String[] entries = notes.split("\n\n");
        for (String entry : entries) {
            entry = entry.trim();
            if (!entry.startsWith("**")) continue;
            int nameEnd = entry.indexOf("**", 2);
            if (nameEnd < 0) continue;
            String expertName = entry.substring(2, nameEnd).trim();
            String note = entry.substring(nameEnd + 2).trim();
            if (note.contains("owner-lock")) {
                blocked.put(expertName, true);
            }
        }
        return blocked;
    }

    // -------------------------------------------------------------------------
    // Approval tracker helpers
    // -------------------------------------------------------------------------

    private void updateApprovalTracker(Suggestion suggestion, ExpertRole expert,
                                        String status, int round) {
        Long id = suggestion.getId();
        if (id == null) return;
        Suggestion fresh = suggestionRepository.findById(id).orElse(suggestion);
        Map<String, Suggestion.ExpertApprovalEntry> tracker = fresh.getExpertApprovalMap();
        tracker.put(expert.getDisplayName(), new Suggestion.ExpertApprovalEntry(status, round));
        fresh.setExpertApprovalMap(tracker);
        suggestionRepository.save(fresh);
    }

    private String getApprovalStatus(Suggestion suggestion, ExpertRole expert) {
        Map<String, Suggestion.ExpertApprovalEntry> tracker = suggestion.getExpertApprovalMap();
        Suggestion.ExpertApprovalEntry entry = tracker.get(expert.getDisplayName());
        return entry != null ? entry.getStatus() : null;
    }

    private void clearApprovalTracker(Suggestion suggestion) {
        suggestion.setExpertApprovalTracker(null);
        suggestionRepository.save(suggestion);
    }

    private void notifyAdminsApprovalNeeded(Suggestion suggestion) {
        slackNotificationService.sendApprovalNeededNotification(suggestion);

        Map<String, Object> payload = Map.of(
                "type", "approval_needed",
                "suggestionId", suggestion.getId() != null ? suggestion.getId() : 0L,
                "suggestionTitle", suggestion.getTitle() != null ? suggestion.getTitle() : ""
        );

        List<User> admins = new ArrayList<>();
        admins.addAll(userRepository.findByRole(UserRole.ROOT_ADMIN));
        admins.addAll(userRepository.findByRole(UserRole.ADMIN));

        for (User admin : admins) {
            if (admin.getUsername() != null) {
                userNotificationHandler.sendNotificationToUser(admin.getUsername(), payload);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Private utility helpers (copied from SuggestionService)
    // -------------------------------------------------------------------------

    private void savePlanTasksFromNode(Long suggestionId, JsonNode tasksNode) {
        Suggestion suggestion = suggestionRepository.findById(suggestionId).orElse(null);
        if (suggestion != null && isPlanLocked(suggestion.getStatus())) {
            log.warn("Blocking plan task changes for suggestion {} — plan is locked in {} state",
                    suggestionId, suggestion.getStatus());
            return;
        }
        planTaskRepository.deleteBySuggestionId(suggestionId);

        int order = 1;
        for (JsonNode taskNode : tasksNode) {
            PlanTask task = new PlanTask();
            task.setSuggestionId(suggestionId);
            task.setTaskOrder(order++);
            task.setTitle(taskNode.has("title") ? taskNode.get("title").asText() : "Task " + (order - 1));
            task.setDescription(taskNode.has("description") ? taskNode.get("description").asText() : null);
            task.setDisplayTitle(taskNode.has("displayTitle")
                    ? taskNode.get("displayTitle").asText() : task.getTitle());
            task.setDisplayDescription(taskNode.has("displayDescription")
                    ? taskNode.get("displayDescription").asText() : task.getDescription());
            task.setEstimatedMinutes(taskNode.has("estimatedMinutes")
                    ? taskNode.get("estimatedMinutes").asInt() : null);
            task.setStatus(TaskStatus.PENDING);
            task.setStatusDetail("Waiting to start");
            planTaskRepository.save(task);
        }
        log.info("Updated {} plan tasks for suggestion {} from expert revision", order - 1, suggestionId);
        broadcastTasks(suggestionId);
    }

    private String buildTasksJsonForExecution(Long suggestionId) {
        List<PlanTask> tasks = planTaskRepository.findBySuggestionIdOrderByTaskOrder(suggestionId);
        if (tasks.isEmpty()) return null;

        StringBuilder sb = new StringBuilder();
        for (PlanTask task : tasks) {
            sb.append("Task ").append(task.getTaskOrder()).append(": ").append(task.getTitle());
            if (task.getDescription() != null) {
                sb.append(" — ").append(task.getDescription());
            }
            if (task.getEstimatedMinutes() != null) {
                sb.append(" (~").append(task.getEstimatedMinutes()).append(" min)");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    private boolean isPlanLocked(SuggestionStatus status) {
        return status == SuggestionStatus.APPROVED
            || status == SuggestionStatus.IN_PROGRESS
            || status == SuggestionStatus.TESTING
            || status == SuggestionStatus.DEV_COMPLETE;
    }

    private boolean isSubstantiveAnalysis(String analysis) {
        if (analysis == null || analysis.isBlank()) return false;
        return analysis.trim().length() >= MIN_EXPERT_ANALYSIS_LENGTH;
    }

    private String extractJsonBlock(String response) {
        int start = response.indexOf('{');
        int end = response.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return response.substring(start, end + 1);
        }
        return null;
    }

    private List<String> extractQuestions(String response) {
        try {
            String json = extractJsonBlock(response);
            if (json != null) {
                JsonNode root = objectMapper.readTree(json);
                JsonNode questionsNode = root.get("questions");
                if (questionsNode != null && questionsNode.isArray()) {
                    return objectMapper.convertValue(questionsNode,
                            new TypeReference<List<String>>() {});
                }
            }
        } catch (Exception e) {
            log.warn("Failed to extract questions from expert response: {}", e.getMessage());
        }
        return null;
    }

    private void broadcastExpertClarificationQuestions(Long suggestionId,
                                                        List<String> questions, ExpertRole expert) {
        try {
            String questionsJson = objectMapper.writeValueAsString(questions);
            webSocketHandler.sendToSuggestion(suggestionId,
                    "{\"type\":\"expert_clarification_questions\",\"questions\":" + questionsJson +
                    ",\"expertName\":\"" + escapeJson(expert.getDisplayName()) + "\"}");
        } catch (JsonProcessingException e) {
            log.error("Failed to broadcast expert clarification questions", e);
        }
    }

    // -------------------------------------------------------------------------
    // Delegation helpers (thin wrappers over messaging helper)
    // -------------------------------------------------------------------------

    private SuggestionMessage addMessage(Long suggestionId, SenderType type, String sender,
                                          String content) {
        return messagingHelper.addMessage(suggestionId, type, sender, content);
    }

    private void broadcastUpdate(Suggestion suggestion) {
        messagingHelper.broadcastUpdate(suggestion);
    }

    private void broadcastTasks(Long suggestionId) {
        messagingHelper.broadcastTasks(suggestionId);
    }

    private void broadcastExpertReviewStatus(Long suggestionId) {
        messagingHelper.broadcastExpertReviewStatus(suggestionId);
    }

    private String escapeJson(String s) {
        return messagingHelper.escapeJson(s);
    }

    // -------------------------------------------------------------------------
    // Inner classes
    // -------------------------------------------------------------------------

    /** Holds the result of a single expert review within a batch. */
    static class ExpertBatchResult {
        final ExpertRole expert;
        final String sessionId;
        final String response;

        ExpertBatchResult(ExpertRole expert, String sessionId, String response) {
            this.expert = expert;
            this.sessionId = sessionId;
            this.response = response;
        }
    }

    /** Holds the result of owner-lock enforcement over proposed task changes. */
    record OwnerLockResult(
            boolean anyBlocked,
            boolean allTaskChangesBlocked,
            String blockedNote,
            JsonNode filteredTasks) {}
}
