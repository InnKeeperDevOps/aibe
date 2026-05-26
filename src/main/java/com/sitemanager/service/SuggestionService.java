package com.sitemanager.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sitemanager.dto.ClarificationRequest;
import com.sitemanager.dto.UpdateDraftRequest;
import com.sitemanager.model.Suggestion;
import com.sitemanager.model.SuggestionMessage;
import com.sitemanager.model.enums.Priority;
import com.sitemanager.model.enums.SenderType;
import com.sitemanager.model.enums.SuggestionStatus;
import com.sitemanager.model.PlanTask;
import com.sitemanager.model.enums.TaskStatus;
import com.sitemanager.repository.PlanTaskRepository;
import com.sitemanager.repository.SuggestionMessageRepository;
import com.sitemanager.repository.SuggestionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import jakarta.persistence.criteria.Predicate;
import java.io.File;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class SuggestionService {

    private static final Logger log = LoggerFactory.getLogger(SuggestionService.class);

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private final SuggestionRepository suggestionRepository;
    private final SuggestionMessageRepository messageRepository;
    private final PlanTaskRepository planTaskRepository;
    private final ClaudeService claudeService;
    private final SiteSettingsService settingsService;
    private final SlackNotificationService slackNotificationService;
    private final SuggestionMessagingHelper messagingHelper;
    private final ExpertReviewService expertReviewService;
    private final PlanExecutionService planExecutionService;

    /**
     * Self-reference so the short {@code @Transactional} persistence methods run
     * through the Spring proxy when invoked from the non-transactional public
     * entry points. This keeps the long-running {@link #triggerAiEvaluation}
     * (which performs a blocking git pull) OUTSIDE the transaction so it does
     * not pin a database connection and exhaust the small HikariCP pool.
     */
    @Autowired
    @Lazy
    private SuggestionService self;

    public SuggestionService(SuggestionRepository suggestionRepository,
                             SuggestionMessageRepository messageRepository,
                             PlanTaskRepository planTaskRepository,
                             ClaudeService claudeService,
                             SiteSettingsService settingsService,
                             SlackNotificationService slackNotificationService,
                             SuggestionMessagingHelper messagingHelper,
                             ExpertReviewService expertReviewService,
                             PlanExecutionService planExecutionService) {
        this.suggestionRepository = suggestionRepository;
        this.messageRepository = messageRepository;
        this.planTaskRepository = planTaskRepository;
        this.claudeService = claudeService;
        this.settingsService = settingsService;
        this.slackNotificationService = slackNotificationService;
        this.messagingHelper = messagingHelper;
        this.expertReviewService = expertReviewService;
        this.planExecutionService = planExecutionService;
    }

    /**
     * On application startup, find any suggestions that were mid-execution
     * (APPROVED, IN_PROGRESS, or TESTING) and resume Claude to continue the plan.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void resumeSuggestionsOnStartup() {
        List<Suggestion> toResume = suggestionRepository.findByStatusIn(
                List.of(SuggestionStatus.APPROVED, SuggestionStatus.IN_PROGRESS, SuggestionStatus.TESTING)
        );

        if (toResume.isEmpty()) {
            log.info("No approved/in-progress suggestions to resume on startup");
            return;
        }

        log.info("Found {} suggestion(s) to resume on startup", toResume.size());

        // Resume IN_PROGRESS/TESTING suggestions first (already running), then try APPROVED
        List<Suggestion> active = toResume.stream()
                .filter(s -> s.getStatus() != SuggestionStatus.APPROVED)
                .toList();
        List<Suggestion> approved = toResume.stream()
                .filter(s -> s.getStatus() == SuggestionStatus.APPROVED)
                .toList();

        for (Suggestion suggestion : active) {
            try {
                resumeInProgressSuggestion(suggestion);
            } catch (Exception e) {
                log.error("Failed to resume suggestion {} on startup: {}", suggestion.getId(), e.getMessage(), e);
                messagingHelper.addMessage(suggestion.getId(), SenderType.SYSTEM, "System",
                        "Something went wrong while resuming work. You can retry.");
                suggestion.setCurrentPhase("Resume failed — can retry");
                suggestionRepository.save(suggestion);
                messagingHelper.broadcastUpdate(suggestion);
            }
        }

        for (Suggestion suggestion : approved) {
            try {
                log.info("Resuming approved suggestion {} — starting execution", suggestion.getId());
                messagingHelper.addMessage(suggestion.getId(), SenderType.SYSTEM, "System",
                        "System restarted. Picking up where we left off...");
                planExecutionService.executeApprovedSuggestion(suggestion);
            } catch (Exception e) {
                log.error("Failed to resume suggestion {} on startup: {}", suggestion.getId(), e.getMessage(), e);
                messagingHelper.addMessage(suggestion.getId(), SenderType.SYSTEM, "System",
                        "Something went wrong while resuming work. You can retry.");
                suggestion.setCurrentPhase("Resume failed — can retry");
                suggestionRepository.save(suggestion);
                messagingHelper.broadcastUpdate(suggestion);
            }
        }
    }

    private void resumeInProgressSuggestion(Suggestion suggestion) {
        String workDir = suggestion.getWorkingDirectory();

        // If no working directory or it doesn't exist, fall back to full re-execution
        if (workDir == null || !new File(workDir).exists()) {
            log.info("Suggestion {} has no valid working directory, re-executing from scratch",
                    suggestion.getId());
            suggestion.setStatus(SuggestionStatus.APPROVED);
            suggestion.setCurrentPhase("Restarting from the beginning...");
            suggestionRepository.save(suggestion);
            messagingHelper.broadcastUpdate(suggestion);
            messagingHelper.addMessage(suggestion.getId(), SenderType.SYSTEM, "System",
                    "System restarted. Starting the work over from the beginning...");
            planExecutionService.executeApprovedSuggestion(suggestion);
            return;
        }

        log.info("Resuming in-progress suggestion {} in {}", suggestion.getId(), workDir);

        messagingHelper.addMessage(suggestion.getId(), SenderType.SYSTEM, "System",
                "System restarted. Resuming work on the next pending task...");

        suggestion.setCurrentPhase("Resuming — checking progress...");
        suggestionRepository.save(suggestion);
        messagingHelper.broadcastUpdate(suggestion);

        // Reset any IN_PROGRESS or REVIEWING tasks to PENDING since we can't know if they completed before crash
        List<PlanTask> inProgressTasks = planTaskRepository.findBySuggestionIdAndStatus(
                suggestion.getId(), TaskStatus.IN_PROGRESS);
        List<PlanTask> reviewingTasks = planTaskRepository.findBySuggestionIdAndStatus(
                suggestion.getId(), TaskStatus.REVIEWING);
        for (PlanTask task : inProgressTasks) {
            task.setStatus(TaskStatus.PENDING);
            task.setStartedAt(null);
            planTaskRepository.save(task);
        }
        for (PlanTask task : reviewingTasks) {
            task.setStatus(TaskStatus.PENDING);
            task.setStartedAt(null);
            planTaskRepository.save(task);
        }

        // Resume sequential task execution — pick up from the next pending task
        planExecutionService.executeNextTask(suggestion.getId());
    }

    public List<Suggestion> getAllSuggestions(String username) {
        if (username != null) {
            return suggestionRepository.findAllExcludingOthersDrafts(username);
        }
        return suggestionRepository.findAllByOrderByCreatedAtDesc();
    }

    public List<Suggestion> getSuggestions(String search, String status, String sortBy, String sortDir,
                                            String priority) {
        Specification<Suggestion> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (search != null && !search.isBlank()) {
                String pattern = "%" + search.toLowerCase() + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("title")), pattern),
                        cb.like(cb.lower(root.get("description")), pattern)
                ));
            }

            if (status != null && !status.isBlank()) {
                try {
                    SuggestionStatus s = SuggestionStatus.valueOf(status.toUpperCase());
                    predicates.add(cb.equal(root.get("status"), s));
                } catch (IllegalArgumentException ignored) {
                    // unknown status — ignore filter
                }
            }

            if (priority != null && !priority.isBlank()) {
                try {
                    Priority p = Priority.valueOf(priority.toUpperCase());
                    predicates.add(cb.equal(root.get("priority"), p));
                } catch (IllegalArgumentException ignored) {
                    // unknown priority — ignore filter
                }
            }

            return predicates.isEmpty() ? cb.conjunction() : cb.and(predicates.toArray(new Predicate[0]));
        };

        boolean sortByPriority = "priority".equalsIgnoreCase(sortBy);
        Sort.Direction dir = "asc".equalsIgnoreCase(sortDir) ? Sort.Direction.ASC : Sort.Direction.DESC;

        if (sortByPriority) {
            // Priority has a meaningful order (HIGH > MEDIUM > LOW); sort in-memory.
            // priorityOrder assigns 0=HIGH, 1=MEDIUM, 2=LOW, so ascending by priorityOrder = HIGH first.
            // "desc" means highest priority first (HIGH, MEDIUM, LOW) → ascending by priorityOrder.
            // "asc" means lowest priority first (LOW, MEDIUM, HIGH) → descending by priorityOrder.
            List<Suggestion> results = suggestionRepository.findAll(spec, Sort.by(Sort.Direction.ASC, "createdAt"));
            int multiplier = dir == Sort.Direction.DESC ? 1 : -1;
            results.sort((a, b) -> multiplier * Integer.compare(priorityOrder(a.getPriority()), priorityOrder(b.getPriority())));
            return results;
        }

        String sortField = switch (sortBy == null ? "" : sortBy.toLowerCase()) {
            case "votes" -> "upVotes";
            case "date" -> "updatedAt";
            default -> "createdAt";
        };

        return suggestionRepository.findAll(spec, Sort.by(dir, sortField));
    }

    private static int priorityOrder(Priority p) {
        if (p == null) return 1;
        return switch (p) {
            case HIGH -> 0;
            case MEDIUM -> 1;
            case LOW -> 2;
        };
    }

    public Optional<Suggestion> getSuggestion(Long id) {
        return suggestionRepository.findById(id);
    }

    public List<SuggestionMessage> getMessages(Long suggestionId) {
        return messageRepository.findBySuggestionIdOrderByCreatedAtAsc(suggestionId);
    }

    @Transactional
    public Suggestion updatePriority(Long id, Priority priority) {
        Suggestion suggestion = suggestionRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Suggestion not found: " + id));
        suggestion.setPriority(priority);
        return suggestionRepository.save(suggestion);
    }

    /**
     * Create a new suggestion. The database row is written in a short
     * transaction ({@link #persistNewSuggestion}); the subsequent AI
     * evaluation runs WITHOUT an open transaction so its blocking git pull
     * does not pin a database connection and exhaust the connection pool.
     */
    public Suggestion createSuggestion(String title, String description, Long authorId, String authorName,
                                       Priority priority, boolean isDraft) {
        Suggestion suggestion = self.persistNewSuggestion(title, description, authorId, authorName,
                priority, isDraft);

        if (isDraft) {
            // Saved as a user draft — skip AI evaluation and notifications
            return suggestion;
        }

        // Trigger AI evaluation outside the transaction (blocking git pull)
        triggerAiEvaluation(suggestion);

        return suggestion;
    }

    @Transactional
    public Suggestion persistNewSuggestion(String title, String description, Long authorId, String authorName,
                                           Priority priority, boolean isDraft) {
        Suggestion suggestion = new Suggestion();
        suggestion.setTitle(title);
        suggestion.setDescription(description);
        suggestion.setAuthorId(authorId);
        suggestion.setAuthorName(authorName != null ? authorName : "Anonymous");
        suggestion.setPriority(priority != null ? priority : Priority.MEDIUM);
        suggestion.setStatus(SuggestionStatus.DRAFT);
        suggestion.setClaudeSessionId(claudeService.generateSessionId());
        suggestion = suggestionRepository.save(suggestion);

        if (isDraft) {
            // Saved as a user draft — skip AI evaluation and notifications
            return suggestion;
        }

        // Add the initial description as the first message
        messagingHelper.addMessage(suggestion.getId(), SenderType.USER, suggestion.getAuthorName(),
                "**" + title + "**\n\n" + description);

        return suggestion;
    }

    @Transactional
    public Suggestion updateDraft(Long id, UpdateDraftRequest req, String username) {
        Suggestion suggestion = suggestionRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Suggestion not found: " + id));
        if (suggestion.getStatus() != SuggestionStatus.DRAFT) {
            throw new IllegalStateException("Suggestion " + id + " is not a draft");
        }
        if (!username.equals(suggestion.getAuthorName())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You do not own this draft");
        }
        suggestion.setTitle(req.getTitle());
        suggestion.setDescription(req.getDescription());
        if (req.getPriority() != null) {
            suggestion.setPriority(req.getPriority());
        }
        suggestion = suggestionRepository.save(suggestion);
        messagingHelper.broadcastUpdate(suggestion);
        return suggestion;
    }

    /**
     * Submit a user draft for AI evaluation. The draft's initial message is
     * persisted in a short transaction ({@link #prepareDraftForSubmission});
     * the evaluation pipeline then runs WITHOUT an open transaction so its
     * blocking git pull does not pin a database connection.
     */
    public Suggestion submitDraft(Long id, String username) {
        Suggestion suggestion = self.prepareDraftForSubmission(id, username);

        // Run the same evaluation pipeline as a normal (non-draft) submission,
        // outside the transaction (blocking git pull)
        triggerAiEvaluation(suggestion);

        return suggestion;
    }

    @Transactional
    public Suggestion prepareDraftForSubmission(Long id, String username) {
        Suggestion suggestion = suggestionRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Suggestion not found: " + id));
        if (suggestion.getStatus() != SuggestionStatus.DRAFT) {
            throw new IllegalStateException("Suggestion " + id + " is not a draft");
        }
        if (!username.equals(suggestion.getAuthorName())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You do not own this draft");
        }

        // Add the initial description as the first message (skipped during draft creation)
        messagingHelper.addMessage(suggestion.getId(), SenderType.USER, suggestion.getAuthorName(),
                "**" + suggestion.getTitle() + "**\n\n" + suggestion.getDescription());

        return suggestion;
    }

    public List<Suggestion> getMyDrafts(String username) {
        return suggestionRepository.findByStatusAndAuthorName(SuggestionStatus.DRAFT, username);
    }

    public void triggerAiEvaluation(Suggestion suggestion) {
        String repoUrl = settingsService.getSettings().getTargetRepoUrl();

        suggestion.setStatus(SuggestionStatus.DISCUSSING);
        suggestion.setCurrentPhase("Getting the latest version of the project...");
        suggestionRepository.save(suggestion);
        messagingHelper.broadcastUpdate(suggestion);

        // Pull latest main-repo/ so Claude always evaluates against the newest code
        if (repoUrl != null && !repoUrl.isBlank()) {
            try {
                boolean changed = claudeService.pullMainRepository(repoUrl);
                log.info("Main repo {} for suggestion {}", changed ? "updated" : "already up to date",
                        suggestion.getId());
            } catch (Exception e) {
                log.warn("Failed to pull main-repo for evaluation session: {}", e.getMessage());
            }
        }

        suggestion.setCurrentPhase("Evaluating your suggestion...");
        suggestionRepository.save(suggestion);
        messagingHelper.broadcastUpdate(suggestion);

        claudeService.evaluateSuggestion(
                suggestion.getTitle(),
                suggestion.getDescription(),
                repoUrl,
                suggestion.getClaudeSessionId(),
                claudeService.getMainRepoDir(),
                progress -> messagingHelper.broadcastProgress(suggestion.getId(), progress)
        ).thenAccept(response -> {
            handleAiResponse(suggestion.getId(), response);
        });
    }

    @Transactional
    public void handleAiResponse(Long suggestionId, String response) {
        Suggestion suggestion = suggestionRepository.findById(suggestionId).orElse(null);
        if (suggestion == null) return;

        suggestion.setLastActivityAt(Instant.now());

        // Log the AI evaluation result
        String responseStatus = response.contains("PLAN_READY") ? "PLAN_READY" :
                response.contains("NEEDS_CLARIFICATION") ? "NEEDS_CLARIFICATION" : "UNKNOWN";
        log.info("[AI-FLOW] suggestion={} evaluation result: status={} responseLength={}",
                suggestionId, responseStatus, response.length());
        log.debug("[AI-FLOW] suggestion={} evaluation response: {}", suggestionId,
                response.length() > 1000 ? response.substring(0, 1000) + "..." : response);

        // Extract the human-readable message from JSON, never show raw JSON in discussion
        String displayMessage = extractMessage(response);

        // Try to parse JSON response to determine status
        if (response.contains("PLAN_READY")) {
            if (isPlanLocked(suggestion.getStatus())) {
                log.warn("Ignoring PLAN_READY for suggestion {} — plan is locked in {} state",
                        suggestionId, suggestion.getStatus());
                return;
            }
            messagingHelper.addMessage(suggestionId, SenderType.AI, "Claude", displayMessage);
            suggestion.setPlanSummary(extractPlan(response));
            suggestion.setPlanDisplaySummary(extractPlanDisplaySummary(response));
            suggestion.setPendingClarificationQuestions(null);
            // A freshly written plan has never been seen by the AI experts
            // on this version. Force admin to send it through expert review
            // (or wait for them to do so) before tasks can be generated.
            suggestion.setExpertsApprovedCurrentPlan(false);

            // Plan is proposed but not yet approved. An admin must click
            // "Approve plan" before expert review starts. Tasks are NOT
            // generated here — they're produced from the final approved plan
            // after all expert reviews converge AND admin re-approves.
            suggestion.setStatus(SuggestionStatus.PLAN_PROPOSED);
            suggestion.setCurrentPhase("Plan proposed — waiting for admin review");
            suggestionRepository.save(suggestion);
            messagingHelper.broadcastUpdate(suggestion);
            return;
        } else if (response.contains("NEEDS_CLARIFICATION")) {
            // Do NOT post clarification questions to the user discussion;
            // they are delivered via WebSocket as structured prompts instead
            suggestion.setStatus(SuggestionStatus.DISCUSSING);
            suggestion.setCurrentPhase("Waiting for your answers");

            List<String> questions = extractQuestions(response);
            if (questions != null && !questions.isEmpty()) {
                try {
                    suggestion.setPendingClarificationQuestions(objectMapper.writeValueAsString(questions));
                } catch (JsonProcessingException e) {
                    log.error("Failed to serialize clarification questions", e);
                }
                messagingHelper.broadcastClarificationQuestions(suggestionId, questions);
            }
        } else {
            messagingHelper.addMessage(suggestionId, SenderType.AI, "Claude", displayMessage);
            suggestion.setStatus(SuggestionStatus.DISCUSSING);
            suggestion.setCurrentPhase("In discussion");
            suggestion.setPendingClarificationQuestions(null);
        }

        suggestionRepository.save(suggestion);
        messagingHelper.broadcastUpdate(suggestion);
    }

    @Transactional
    public void handleClarificationAnswers(Long suggestionId, String senderName,
                                            List<ClarificationRequest.ClarificationAnswer> answers) {
        Suggestion suggestion = suggestionRepository.findById(suggestionId)
                .orElseThrow(() -> new IllegalArgumentException("Suggestion not found"));

        suggestion.setLastActivityAt(Instant.now());
        suggestion.setPendingClarificationQuestions(null);
        suggestionRepository.save(suggestion);

        // Format the Q&A pairs as a structured message
        StringBuilder formattedMessage = new StringBuilder();
        for (ClarificationRequest.ClarificationAnswer qa : answers) {
            formattedMessage.append("**Q: ").append(qa.getQuestion()).append("**\n");
            formattedMessage.append("A: ").append(qa.getAnswer()).append("\n\n");
        }
        String userMessage = formattedMessage.toString().trim();

        // Store the combined clarification response as a user message
        messagingHelper.addMessage(suggestionId, SenderType.USER, senderName, userMessage);

        // Build the prompt for Claude with the structured answers
        StringBuilder claudePrompt = new StringBuilder();
        claudePrompt.append("The user has provided the following clarification answers:\n\n");
        for (ClarificationRequest.ClarificationAnswer qa : answers) {
            claudePrompt.append("Question: ").append(qa.getQuestion()).append("\n");
            claudePrompt.append("Answer: ").append(qa.getAnswer()).append("\n\n");
        }
        claudePrompt.append("Based on these answers and the original suggestion, please evaluate again:\n");
        claudePrompt.append("1. If you still need more information, respond with NEEDS_CLARIFICATION status and a new set of questions.\n");
        claudePrompt.append("2. If you now have enough information, create a COMPLETE implementation plan and respond with PLAN_READY status.\n\n");
        claudePrompt.append("PLAN COMPLETENESS RULES:\n");
        claudePrompt.append("- The plan MUST contain ALL work needed to implement the suggestion end-to-end. " +
                "Cover backend, frontend, data model, migrations, tests, configuration, and any user-visible changes. " +
                "Do not leave gaps that would need to be filled in later.\n");
        claudePrompt.append("- DO NOT produce a tasks list at this stage. Tasks will be generated AFTER the plan has been " +
                "reviewed and approved. Focus entirely on the plan text.\n\n");
        claudePrompt.append("DUAL-LEVEL DETAIL RULES:\n");
        claudePrompt.append("- The plan has TWO layers and you MUST produce BOTH:\n");
        claudePrompt.append("  * LOW-LEVEL (technical, field name 'plan'): for experts reviewing the implementation. " +
                "Reference specific files, classes, methods, modules, frameworks, APIs, schemas, and concrete implementation steps. " +
                "Be specific enough that a developer can verify the plan and so a downstream task generator can break it into actionable steps.\n");
        claudePrompt.append("  * HIGH-LEVEL (display, field name 'planDisplaySummary'): for the end user. Plain, non-technical language " +
                "describing features, behaviors, and outcomes. NEVER mention file names, classes, frameworks, or technical specifics in the display layer.\n");
        claudePrompt.append("- The two layers describe the same work at different granularities — they should NOT be identical strings.\n");
        claudePrompt.append("- The 'message' field (user-facing) and any 'questions' MUST stay plain, non-technical, like the high-level layer.\n");
        claudePrompt.append("- Questions should be about desired behavior and outcomes, not technical choices.\n\n");
        claudePrompt.append("Respond in this JSON format:\n");
        claudePrompt.append("If clarification needed:\n");
        claudePrompt.append("{\"status\": \"NEEDS_CLARIFICATION\", ");
        claudePrompt.append("\"message\": \"brief summary of what you still need to know\", ");
        claudePrompt.append("\"questions\": [\"specific question 1\", \"specific question 2\", ...]}\n\n");
        claudePrompt.append("If ready to plan:\n");
        claudePrompt.append("{\"status\": \"PLAN_READY\", ");
        claudePrompt.append("\"message\": \"your response to the user — plain language\", ");
        claudePrompt.append("\"plan\": \"COMPLETE low-level technical implementation plan referencing concrete files/components/approach — covers all work end-to-end\", ");
        claudePrompt.append("\"planDisplaySummary\": \"high-level plain-language plan summary for the user\"}\n\n");
        claudePrompt.append("IMPORTANT: When status is NEEDS_CLARIFICATION, you MUST include a \"questions\" array with each clarifying question as a separate string element.\n");
        claudePrompt.append("When status is PLAN_READY, you MUST include BOTH layers: 'plan' (low-level) and 'planDisplaySummary' (high-level). ");
        claudePrompt.append("DO NOT include a 'tasks' array — tasks are generated later from the approved plan, not here. ");
        claudePrompt.append("The two layers should differ meaningfully — low-level has technical specifics, high-level has plain language.");

        // Continue the Claude conversation
        suggestion.setCurrentPhase("Reviewing your answers...");
        suggestionRepository.save(suggestion);
        messagingHelper.broadcastUpdate(suggestion);

        String context = buildConversationContext(suggestionId);
        claudeService.continueConversation(
                suggestion.getClaudeSessionId(),
                claudePrompt.toString(),
                context,
                claudeService.getMainRepoDir(),
                progress -> messagingHelper.broadcastProgress(suggestionId, progress)
        ).thenAccept(response -> {
            handleAiResponse(suggestionId, response);
        });
    }

    // --- Expert Review Pipeline (delegated to ExpertReviewService) ---

    @Transactional
    public void handleExpertClarificationAnswers(Long suggestionId, String senderName,
                                                   List<ClarificationRequest.ClarificationAnswer> answers) {
        expertReviewService.handleExpertClarificationAnswers(suggestionId, senderName, answers);
    }

    public Map<String, Object> getExpertReviewStatus(Long suggestionId) {
        return expertReviewService.getExpertReviewStatus(suggestionId);
    }

    public List<Map<String, Object>> getReviewSummary(Long suggestionId) {
        return expertReviewService.getReviewSummary(suggestionId);
    }

    private boolean isPlanLocked(SuggestionStatus status) {
        return status == SuggestionStatus.APPROVED
            || status == SuggestionStatus.IN_PROGRESS
            || status == SuggestionStatus.TESTING
            || status == SuggestionStatus.DEV_COMPLETE;
    }

    @Transactional
    public void handleUserReply(Long suggestionId, String senderName, String message) {
        Suggestion suggestion = suggestionRepository.findById(suggestionId)
                .orElseThrow(() -> new IllegalArgumentException("Suggestion not found"));

        suggestion.setLastActivityAt(Instant.now());
        suggestionRepository.save(suggestion);

        messagingHelper.addMessage(suggestionId, SenderType.USER, senderName, message);

        // Continue the Claude conversation
        suggestion.setCurrentPhase("Processing your response...");
        suggestionRepository.save(suggestion);
        messagingHelper.broadcastUpdate(suggestion);

        String context = buildConversationContext(suggestionId);
        claudeService.continueConversation(
                suggestion.getClaudeSessionId(),
                message,
                context,
                claudeService.getMainRepoDir(),
                progress -> messagingHelper.broadcastProgress(suggestionId, progress)
        ).thenAccept(response -> {
            handleAiResponse(suggestionId, response);
        });
    }

    @Transactional
    public Suggestion approveSuggestion(Long suggestionId) {
        Suggestion suggestion = suggestionRepository.findById(suggestionId)
                .orElseThrow(() -> new IllegalArgumentException("Suggestion not found"));

        if (suggestion.getStatus() == SuggestionStatus.DENIED) {
            throw new IllegalStateException("Cannot approve a denied suggestion (id: " + suggestionId + ")");
        }

        suggestion.setStatus(SuggestionStatus.APPROVED);
        suggestion.setCurrentPhase("Approved — getting ready to start");
        suggestionRepository.save(suggestion);

        messagingHelper.addMessage(suggestionId, SenderType.SYSTEM, "System", "This suggestion has been **approved** and work will begin shortly.");
        messagingHelper.broadcastUpdate(suggestion);
        slackNotificationService.sendNotification(suggestion, "APPROVED");

        // Begin execution
        planExecutionService.executeApprovedSuggestion(suggestion);

        return suggestion;
    }

    @Transactional
    public Suggestion forceReApproval(Long suggestionId) {
        Suggestion suggestion = suggestionRepository.findById(suggestionId)
                .orElseThrow(() -> new IllegalArgumentException("Suggestion not found"));

        SuggestionStatus current = suggestion.getStatus();
        if (current != SuggestionStatus.PLANNED && current != SuggestionStatus.APPROVED) {
            throw new IllegalStateException(
                    "Force re-approval is only available for PLANNED or APPROVED suggestions (current: " + current + ")");
        }

        suggestion.setStatus(SuggestionStatus.EXPERT_REVIEW);
        suggestion.setExpertReviewStep(0);
        suggestion.setExpertReviewRound(1);
        suggestion.setTotalExpertReviewRounds(1);
        suggestion.setExpertReviewNotes(null);
        suggestion.setExpertReviewPlanChanged(false);
        suggestion.setExpertReviewChangedDomains(null);
        suggestion.setCurrentPhase("Force re-approval — restarting expert reviews...");
        suggestionRepository.save(suggestion);

        messagingHelper.addMessage(suggestionId, SenderType.SYSTEM, "System",
                "An admin has requested **force re-approval** — all expert reviewers will re-evaluate the plan.");
        messagingHelper.broadcastUpdate(suggestion);

        expertReviewService.startExpertReviewPipeline(suggestionId);

        return suggestion;
    }

    public Map<String, Object> getExecutionQueueStatus() {
        return planExecutionService.getExecutionQueueStatus();
    }

    @Transactional
    public Suggestion retrySuggestion(Long id, String username) {
        Suggestion suggestion = suggestionRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Suggestion not found"));

        if (!username.equals(suggestion.getAuthorName())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You do not own this suggestion");
        }

        boolean isInProgress = suggestion.getStatus() == SuggestionStatus.IN_PROGRESS;
        boolean hasFailed = suggestion.getCurrentPhase() != null
                && suggestion.getCurrentPhase().toLowerCase().contains("failed");
        if (!isInProgress || !hasFailed) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Suggestion is not in a retryable failed state");
        }

        log.info("[AI-FLOW] suggestion={} user={} retrying failed tasks", id, username);

        List<PlanTask> failedTasks = planTaskRepository.findBySuggestionIdAndStatus(id, TaskStatus.FAILED);
        for (PlanTask task : failedTasks) {
            task.setStatus(TaskStatus.PENDING);
            task.setRetryCount(0);
            task.setFailureReason(null);
        }
        planTaskRepository.saveAll(failedTasks);

        suggestion.setFailureReason(null);
        suggestion.setCurrentPhase("Retrying after failure");
        suggestionRepository.save(suggestion);
        messagingHelper.broadcastUpdate(suggestion);

        planExecutionService.executeNextTask(suggestion.getId());

        return suggestion;
    }

    public Suggestion retryExecution(Long suggestionId) {
        Suggestion suggestion = suggestionRepository.findById(suggestionId)
                .orElseThrow(() -> new IllegalStateException("Suggestion not found"));

        String phase = suggestion.getCurrentPhase();
        if (phase == null || !phase.contains("can retry")) {
            throw new IllegalStateException("Suggestion is not in a retryable state");
        }

        log.info("[AI-FLOW] suggestion={} retrying execution (was: {})", suggestionId, phase);

        // Reset to APPROVED so executeApprovedSuggestion picks it up cleanly
        suggestion.setStatus(SuggestionStatus.APPROVED);
        suggestion.setCurrentPhase("Retrying...");
        suggestion.setWorkingDirectory(null);
        suggestionRepository.save(suggestion);
        messagingHelper.broadcastUpdate(suggestion);

        messagingHelper.addMessage(suggestionId, SenderType.SYSTEM, "System",
                "An admin has requested a retry. Re-starting execution...");

        planExecutionService.executeApprovedSuggestion(suggestion);
        return suggestion;
    }

    /**
     * Restart the whole plan from task 1 against a fresh copy of the repository.
     * Resets every plan task to PENDING and drops the working directory so
     * {@link PlanExecutionService#executeApprovedSuggestion} re-clones the repo
     * at {@code main} and recreates the suggestion branch — discarding any
     * partial or broken work from the previous run.
     */
    public Suggestion restartPlanExecution(Long suggestionId) {
        Suggestion suggestion = suggestionRepository.findById(suggestionId)
                .orElseThrow(() -> new IllegalStateException("Suggestion not found"));

        SuggestionStatus status = suggestion.getStatus();
        boolean restartable = status == SuggestionStatus.IN_PROGRESS
                || status == SuggestionStatus.TESTING
                || status == SuggestionStatus.APPROVED
                || status == SuggestionStatus.DEV_COMPLETE;
        if (!restartable) {
            throw new IllegalStateException(
                    "The plan can only be restarted while the suggestion is being implemented");
        }

        log.info("[AI-FLOW] suggestion={} restarting full plan with a fresh repo (was: {})",
                suggestionId, suggestion.getCurrentPhase());

        // Reset every task back to PENDING so execution starts from task 1.
        List<PlanTask> tasks = planTaskRepository.findBySuggestionIdOrderByTaskOrder(suggestionId);
        for (PlanTask task : tasks) {
            task.setStatus(TaskStatus.PENDING);
            task.setRetryCount(0);
            task.setFailureReason(null);
            task.setStartedAt(null);
            task.setCompletedAt(null);
            task.setStatusDetail("Waiting to start");
        }
        planTaskRepository.saveAll(tasks);

        // Drop the working directory so executeApprovedSuggestion re-clones a
        // fresh copy of the repo at main and recreates the suggestion branch.
        suggestion.setStatus(SuggestionStatus.APPROVED);
        suggestion.setCurrentPhase("Restarting the plan from the beginning...");
        suggestion.setFailureReason(null);
        suggestion.setWorkingDirectory(null);
        suggestionRepository.save(suggestion);
        messagingHelper.broadcastUpdate(suggestion);
        messagingHelper.broadcastTasks(suggestionId);

        messagingHelper.addMessage(suggestionId, SenderType.SYSTEM, "System",
                "Restarting the implementation from the beginning with a fresh copy of the "
                        + "repository (reset to main). All previous work on this suggestion is discarded.");

        planExecutionService.executeApprovedSuggestion(suggestion);
        return suggestion;
    }

    /**
     * Admin approves the current plan. There are two paths:
     *   1. The most recent AI expert review pass already approved this exact
     *      plan (expertsApprovedCurrentPlan == true) — both gates are satisfied
     *      now, so jump straight to task generation.
     *   2. Otherwise, send the plan to AI expert review. Status returns to
     *      PLAN_PROPOSED after experts converge so admin can re-review the
     *      (possibly revised) plan.
     */
    @Transactional
    public Suggestion approvePlan(Long suggestionId) {
        Suggestion suggestion = suggestionRepository.findById(suggestionId)
                .orElseThrow(() -> new IllegalStateException("Suggestion not found"));

        if (suggestion.getStatus() != SuggestionStatus.PLAN_PROPOSED) {
            throw new IllegalStateException(
                    "Only a PLAN_PROPOSED suggestion can have its plan approved (current: "
                    + suggestion.getStatus() + ")");
        }
        if (suggestion.getPlanSummary() == null || suggestion.getPlanSummary().isBlank()) {
            throw new IllegalStateException("Cannot approve an empty plan");
        }

        if (suggestion.isExpertsApprovedCurrentPlan()) {
            // Both gates met: admin approves the same plan version the experts
            // just signed off on. Skip another expert pass and generate tasks.
            messagingHelper.addMessage(suggestionId, SenderType.SYSTEM, "System",
                    "Plan approved by admin. AI experts already approved this plan version. " +
                    "Generating the task list now.");
            expertReviewService.generateTasksFromApprovedPlan(suggestion);
            return suggestion;
        }

        // Plan has not been approved by experts on this version yet — start
        // (or restart) the AI expert review pass.
        suggestion.setStatus(SuggestionStatus.EXPERT_REVIEW);
        suggestion.setExpertReviewStep(0);
        suggestion.setExpertReviewRound(1);
        suggestion.setTotalExpertReviewRounds(1);
        suggestion.setExpertReviewNotes(null);
        suggestion.setExpertReviewPlanChanged(false);
        suggestion.setCurrentPhase("Plan approved by admin — starting AI expert reviews...");
        suggestion.setLastActivityAt(Instant.now());
        suggestionRepository.save(suggestion);

        messagingHelper.addMessage(suggestionId, SenderType.SYSTEM, "System",
                "Plan approved by admin. AI expert reviews will now run; the plan will come back " +
                "for your approval again once they finish.");
        messagingHelper.broadcastUpdate(suggestion);

        expertReviewService.startExpertReviewPipeline(suggestionId);
        return suggestion;
    }

    /**
     * Admin requests changes to the current plan. Feeds the free-text feedback
     * to the main AI model via continueConversation. The existing handleAiResponse
     * path then either lands at PLAN_PROPOSED with a revised plan or returns the
     * suggestion to DISCUSSING with NEEDS_CLARIFICATION questions.
     */
    @Transactional
    public Suggestion requestPlanChanges(Long suggestionId, String feedback) {
        Suggestion suggestion = suggestionRepository.findById(suggestionId)
                .orElseThrow(() -> new IllegalStateException("Suggestion not found"));

        if (suggestion.getStatus() != SuggestionStatus.PLAN_PROPOSED) {
            throw new IllegalStateException(
                    "Plan changes can only be requested while the suggestion is in PLAN_PROPOSED (current: "
                    + suggestion.getStatus() + ")");
        }
        if (feedback == null || feedback.isBlank()) {
            throw new IllegalStateException("Feedback cannot be empty");
        }

        // Any plan edit invalidates the prior expert-approval; the revised
        // plan has never been seen by the latest expert pass.
        suggestion.setExpertsApprovedCurrentPlan(false);
        suggestion.setStatus(SuggestionStatus.DISCUSSING);
        suggestion.setCurrentPhase("Revising the plan based on your feedback...");
        suggestion.setLastActivityAt(Instant.now());
        suggestionRepository.save(suggestion);

        messagingHelper.addMessage(suggestionId, SenderType.USER,
                suggestion.getAuthorName(),
                "**Plan-change request:**\n\n" + feedback);
        messagingHelper.broadcastUpdate(suggestion);

        StringBuilder prompt = new StringBuilder();
        prompt.append("The admin has reviewed the current plan and requested changes.\n\n");

        // Embed the current plan text inline so Claude can reason about it
        // directly — the chat history only contains friendly 'message' fields,
        // not the actual plan content, so clarification questions would be
        // useless without this.
        String currentLowLevel = suggestion.getPlanSummary();
        String currentHighLevel = suggestion.getPlanDisplaySummary();
        if (currentLowLevel != null && !currentLowLevel.isBlank()) {
            prompt.append("CURRENT LOW-LEVEL (technical) plan:\n");
            prompt.append(currentLowLevel).append("\n\n");
        }
        if (currentHighLevel != null && !currentHighLevel.isBlank()
                && !currentHighLevel.equals(currentLowLevel)) {
            prompt.append("CURRENT HIGH-LEVEL (user-facing) plan:\n");
            prompt.append(currentHighLevel).append("\n\n");
        }

        prompt.append("Admin feedback on the plan above:\n\n");
        prompt.append(feedback).append("\n\n");
        prompt.append("Based on this feedback and the plan text above, decide between:\n");
        prompt.append("1. If the feedback is clear enough to act on, produce a REVISED plan that " +
                "incorporates the requested changes. Respond with PLAN_READY.\n");
        prompt.append("2. If the feedback is ambiguous, references something not in the current plan, " +
                "or you need more information to act, ask clarifying questions ANCHORED to specific " +
                "parts of the plan shown above. Respond with NEEDS_CLARIFICATION. Do this until you " +
                "have enough to revise the plan. Reference the relevant plan text in your questions " +
                "so the admin knows what you're asking about.\n\n");
        prompt.append("PLAN COMPLETENESS RULES:\n");
        prompt.append("- The revised plan MUST contain ALL work needed to implement the suggestion " +
                "end-to-end. Do not drop existing scope unless the admin explicitly asked.\n");
        prompt.append("- DO NOT produce a tasks list. Tasks will be generated later from the approved plan.\n\n");
        prompt.append("DUAL-LEVEL DETAIL RULES:\n");
        prompt.append("- 'plan' is the low-level technical version (may reference files/classes/frameworks).\n");
        prompt.append("- 'planDisplaySummary' is the high-level plain-language version for the user.\n");
        prompt.append("- They must differ meaningfully.\n\n");
        prompt.append("Respond in this JSON format:\n");
        prompt.append("If clarification needed:\n");
        prompt.append("{\"status\": \"NEEDS_CLARIFICATION\", ");
        prompt.append("\"message\": \"brief summary of what you still need to know\", ");
        prompt.append("\"questions\": [\"specific question 1\", \"specific question 2\", ...]}\n\n");
        prompt.append("If ready to plan:\n");
        prompt.append("{\"status\": \"PLAN_READY\", ");
        prompt.append("\"message\": \"plain-language summary of how you incorporated the feedback\", ");
        prompt.append("\"plan\": \"COMPLETE low-level technical plan covering all work end-to-end\", ");
        prompt.append("\"planDisplaySummary\": \"high-level plain-language plan summary for the user\"}");

        String context = buildConversationContext(suggestionId);
        claudeService.continueConversation(
                suggestion.getClaudeSessionId(),
                prompt.toString(),
                context,
                claudeService.getMainRepoDir(),
                progress -> messagingHelper.broadcastProgress(suggestionId, progress)
        ).thenAccept(response -> {
            handleAiResponse(suggestionId, response);
        });

        return suggestion;
    }

    /**
     * Wipe the plan, tasks, and discussion thread, then re-run the initial AI
     * evaluation from step 1. Preserves the suggestion's title, description,
     * author, priority, and votes. Used when the plan went off-track and the
     * admin wants Claude to start over with a fresh session.
     */
    @Transactional
    public Suggestion restartFromScratch(Long suggestionId) {
        Suggestion suggestion = suggestionRepository.findById(suggestionId)
                .orElseThrow(() -> new IllegalStateException("Suggestion not found"));

        SuggestionStatus status = suggestion.getStatus();
        if (status == SuggestionStatus.MERGED) {
            throw new IllegalStateException("Cannot redo a suggestion that has already been merged.");
        }
        if (status == SuggestionStatus.DRAFT) {
            throw new IllegalStateException("This suggestion is still a draft — nothing to redo yet.");
        }

        log.info("[AI-FLOW] suggestion={} restart-from-scratch: wiping plan, tasks, and messages (was: {})",
                suggestionId, status);

        // Wipe the plan tasks and the discussion thread.
        planTaskRepository.deleteBySuggestionId(suggestionId);
        List<SuggestionMessage> messages = messageRepository.findBySuggestionIdOrderByCreatedAtAsc(suggestionId);
        if (!messages.isEmpty()) {
            messageRepository.deleteAll(messages);
        }

        // Clear plan, expert-review, PR, and working-directory state. A fresh
        // claudeSessionId is generated so Claude starts a brand-new conversation.
        suggestion.setPlanSummary(null);
        suggestion.setPlanDisplaySummary(null);
        suggestion.setPendingClarificationQuestions(null);
        suggestion.setClaudeSessionId(java.util.UUID.randomUUID().toString());
        suggestion.setWorkingDirectory(null);
        suggestion.setPrUrl(null);
        suggestion.setPrNumber(null);
        suggestion.setChangelogEntry(null);
        suggestion.setFailureReason(null);
        suggestion.setExpertReviewStep(null);
        suggestion.setExpertReviewRound(null);
        suggestion.setExpertReviewNotes(null);
        suggestion.setExpertReviewPlanChanged(null);
        suggestion.setTotalExpertReviewRounds(null);
        suggestion.setExpertReviewChangedDomains(null);
        suggestion.setExpertApprovalTracker(null);
        suggestion.setOwnerLockedPlanSections(null);
        suggestion.setStatus(SuggestionStatus.DISCUSSING);
        suggestion.setCurrentPhase("Restarting from step 1...");
        suggestion.setLastActivityAt(Instant.now());
        suggestionRepository.save(suggestion);

        // Seed the now-empty thread with the original suggestion text so the
        // discussion still has context, then announce the redo.
        messagingHelper.addMessage(suggestionId, SenderType.USER, suggestion.getAuthorName(),
                "**" + suggestion.getTitle() + "**\n\n" + suggestion.getDescription());
        messagingHelper.addMessage(suggestionId, SenderType.SYSTEM, "System",
                "Plan and discussion have been wiped. Re-running the initial AI evaluation from step 1.");

        messagingHelper.broadcastUpdate(suggestion);
        messagingHelper.broadcastTasks(suggestionId);

        triggerAiEvaluation(suggestion);
        return suggestion;
    }

    public Map<String, Object> retryPrCreation(Long suggestionId) {
        return planExecutionService.retryPrCreation(suggestionId);
    }

    public Map<String, Object> retryMerge(Long suggestionId) {
        return planExecutionService.retryMergeForSuggestion(suggestionId);
    }

    /**
     * Resume an in-progress suggestion from the last successful step. Two cases:
     * <ul>
     *   <li>Some tasks are still unfinished (IN_PROGRESS / TESTING): every
     *       COMPLETED task is left alone so its committed work stays on the
     *       branch, and every non-COMPLETED task is reset to a fresh PENDING.
     *       Execution picks up from the first non-completed task.</li>
     *   <li>All tasks are COMPLETED but the post-task pipeline (commit / push
     *       / PR creation) failed (status DEV_COMPLETE with a "failed" phase):
     *       the "last successful step" was the last task itself, so the
     *       commit-push-PR pipeline is re-run via {@link
     *       PlanExecutionService#createPrAsync(Long)}.</li>
     * </ul>
     * In both cases the working directory and suggestion branch are preserved
     * — this is a "continue from where it broke" retry, not a fresh re-clone
     * like Restart Plan.
     */
    public Suggestion retryFromLastSuccessful(Long suggestionId) {
        Suggestion suggestion = suggestionRepository.findById(suggestionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Suggestion not found"));

        SuggestionStatus status = suggestion.getStatus();
        boolean restartable = status == SuggestionStatus.IN_PROGRESS
                || status == SuggestionStatus.TESTING
                || status == SuggestionStatus.DEV_COMPLETE;
        if (!restartable) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This suggestion isn't being implemented right now — nothing to retry from");
        }

        List<PlanTask> all = planTaskRepository.findBySuggestionIdOrderByTaskOrder(suggestionId);
        List<PlanTask> unfinished = all.stream()
                .filter(t -> t.getStatus() != TaskStatus.COMPLETED)
                .toList();

        // Case 2: every task is done, but a post-task step (commit/push/PR)
        // failed. The "last successful step" is the last completed task —
        // re-run the submission pipeline from there.
        if (unfinished.isEmpty()) {
            String phase = suggestion.getCurrentPhase();
            boolean postTaskFailed = status == SuggestionStatus.DEV_COMPLETE
                    && phase != null && phase.toLowerCase().contains("fail");
            if (!postTaskFailed) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "All tasks are already completed — nothing to retry");
            }
            log.info("[AI-FLOW] suggestion={} all tasks done but post-task pipeline failed ({}); "
                    + "re-running commit → push → PR", suggestionId, phase);
            suggestion.setFailureReason(null);
            suggestion.setCurrentPhase("Re-submitting the changes...");
            suggestionRepository.save(suggestion);
            messagingHelper.broadcastUpdate(suggestion);
            messagingHelper.addMessage(suggestionId, SenderType.SYSTEM, "System",
                    "Re-running the submission step from the last completed task "
                            + "(staging, committing, pushing, and opening the review request).");
            planExecutionService.createPrAsync(suggestionId);
            return suggestion;
        }

        // Case 1: some tasks haven't completed — restart them.
        long completedCount = all.size() - unfinished.size();
        log.info("[AI-FLOW] suggestion={} resuming from last successful task ({} of {} tasks already completed)",
                suggestionId, completedCount, all.size());

        // Reset every non-COMPLETED task to a fresh PENDING — COMPLETED tasks
        // stay as-is so their committed work is preserved on the branch.
        for (PlanTask t : unfinished) {
            t.setStatus(TaskStatus.PENDING);
            t.setRetryCount(0);
            t.setFailureReason(null);
            t.setStartedAt(null);
            t.setCompletedAt(null);
            t.setStatusDetail("Waiting to start");
        }
        planTaskRepository.saveAll(unfinished);

        suggestion.setFailureReason(null);
        suggestion.setCurrentPhase("Resuming after the last successful task...");
        suggestionRepository.save(suggestion);
        messagingHelper.broadcastUpdate(suggestion);
        messagingHelper.broadcastTasks(suggestionId);

        messagingHelper.addMessage(suggestionId, SenderType.SYSTEM, "System",
                "Picking up after the last successful task (" + completedCount + "/" + all.size()
                        + " already done). Keeping the existing workspace and branch.");

        planExecutionService.executeNextTask(suggestionId);
        return suggestion;
    }

    public List<PlanTask> getPlanTasks(Long suggestionId) {
        return planTaskRepository.findBySuggestionIdOrderByTaskOrder(suggestionId);
    }


    @Transactional
    public Suggestion denySuggestion(Long suggestionId, String reason) {
        Suggestion suggestion = suggestionRepository.findById(suggestionId)
                .orElseThrow(() -> new IllegalArgumentException("Suggestion not found"));

        boolean wasActive = suggestion.getStatus() == SuggestionStatus.IN_PROGRESS
                || suggestion.getStatus() == SuggestionStatus.TESTING
                || suggestion.getStatus() == SuggestionStatus.APPROVED;

        suggestion.setStatus(SuggestionStatus.DENIED);
        suggestion.setCurrentPhase("Denied");
        suggestionRepository.save(suggestion);

        String msg = "Suggestion has been **denied** by an administrator.";
        if (reason != null && !reason.isBlank()) {
            msg += "\nReason: " + reason;
        }
        messagingHelper.addMessage(suggestionId, SenderType.SYSTEM, "System", msg);
        messagingHelper.broadcastUpdate(suggestion);
        slackNotificationService.sendNotification(suggestion, "DENIED");

        if (wasActive) {
            planExecutionService.tryStartNextQueuedSuggestion();
        }

        return suggestion;
    }

    @Scheduled(fixedRate = 60000) // Check every minute
    public void checkTimeouts() {
        int timeout = settingsService.getSettings().getSuggestionTimeoutMinutes();
        Instant cutoff = Instant.now().minus(timeout, ChronoUnit.MINUTES);

        List<Suggestion> stale = suggestionRepository.findByStatusInAndLastActivityAtBefore(
                List.of(SuggestionStatus.DRAFT, SuggestionStatus.DISCUSSING, SuggestionStatus.EXPERT_REVIEW),
                cutoff
        );

        for (Suggestion s : stale) {
            s.setStatus(SuggestionStatus.TIMED_OUT);
            s.setCurrentPhase("Timed out due to inactivity");
            suggestionRepository.save(s);
            messagingHelper.addMessage(s.getId(), SenderType.SYSTEM, "System",
                    "This suggestion has been closed due to inactivity.");
            messagingHelper.broadcastUpdate(s);
        }
    }

    private List<String> extractQuestions(String response) {
        try {
            // Try to find JSON in the response
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
            log.warn("Failed to extract questions from AI response: {}", e.getMessage());
        }
        return null;
    }

    private String extractJsonBlock(String response) {
        // Find the first { and last } to extract the JSON object
        int start = response.indexOf('{');
        int end = response.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return response.substring(start, end + 1);
        }
        return null;
    }

    public List<String> getPendingQuestions(Long suggestionId) {
        Suggestion suggestion = suggestionRepository.findById(suggestionId).orElse(null);
        if (suggestion == null || suggestion.getPendingClarificationQuestions() == null) {
            return null;
        }
        try {
            return objectMapper.readValue(suggestion.getPendingClarificationQuestions(),
                    new TypeReference<List<String>>() {});
        } catch (JsonProcessingException e) {
            log.error("Failed to parse pending questions", e);
            return null;
        }
    }

    private String buildConversationContext(Long suggestionId) {
        List<SuggestionMessage> messages = messageRepository.findBySuggestionIdOrderByCreatedAtAsc(suggestionId);
        if (messages == null || messages.isEmpty()) {
            return null;
        }

        StringBuilder context = new StringBuilder();
        for (SuggestionMessage msg : messages) {
            switch (msg.getSenderType()) {
                case USER:
                    context.append("[User (").append(msg.getSenderName()).append(")]: ");
                    break;
                case AI:
                    context.append("[Assistant]: ");
                    break;
                case SYSTEM:
                    context.append("[System]: ");
                    break;
            }
            context.append(msg.getContent()).append("\n\n");
        }
        return context.toString().trim();
    }

    private String extractMessage(String response) {
        try {
            String json = extractJsonBlock(response);
            if (json != null) {
                JsonNode root = objectMapper.readTree(json);
                JsonNode messageNode = root.get("message");
                if (messageNode != null && messageNode.isTextual()) {
                    return messageNode.asText();
                }
            }
        } catch (Exception e) {
            log.warn("Failed to extract message from AI response: {}", e.getMessage());
        }
        // Fallback: return the raw response if no JSON message found
        return response;
    }

    private String extractPlan(String response) {
        // Try to extract plan from JSON response
        try {
            String json = extractJsonBlock(response);
            if (json != null) {
                JsonNode root = objectMapper.readTree(json);
                if (root.has("plan")) {
                    return root.get("plan").asText();
                }
            }
        } catch (Exception e) {
            log.warn("Failed to extract plan via JSON parsing, falling back to string parsing");
        }
        int planIdx = response.indexOf("\"plan\"");
        if (planIdx >= 0) {
            int start = response.indexOf("\"", planIdx + 6);
            if (start >= 0) {
                start++;
                int end = findClosingQuote(response, start);
                if (end > start) {
                    return response.substring(start, end).replace("\\n", "\n");
                }
            }
        }
        return response;
    }

    private String extractPlanDisplaySummary(String response) {
        try {
            String json = extractJsonBlock(response);
            if (json != null) {
                JsonNode root = objectMapper.readTree(json);
                if (root.has("planDisplaySummary")) {
                    return root.get("planDisplaySummary").asText();
                }
            }
        } catch (Exception e) {
            log.warn("Failed to extract planDisplaySummary: {}", e.getMessage());
        }
        // Legacy fallback: when Claude only returns 'plan', mirror it into the
        // display field so the user-facing UI has something to show. New
        // prompts ask for both layers explicitly, so this only fires for
        // older responses or off-prompt output.
        return extractPlan(response);
    }

    private int findClosingQuote(String s, int start) {
        for (int i = start; i < s.length(); i++) {
            if (s.charAt(i) == '"' && s.charAt(i - 1) != '\\') {
                return i;
            }
        }
        return s.length();
    }

    /**
     * When main-repo is re-cloned (settings changed), have Claude merge main into all active
     * suggestion repo branches — resolving any conflicts intelligently — and re-evaluate impact.
     */
    @Async
    @EventListener
    public void onMainRepoUpdated(MainRepoUpdatedEvent event) {
        log.info("Main repo updated, asking Claude to merge into active suggestion repos...");

        List<String> suggestionRepoDirs = claudeService.findSuggestionRepoDirs();
        if (suggestionRepoDirs.isEmpty()) {
            log.info("No suggestion repos to merge");
            return;
        }

        // Find all active suggestions that have a working directory
        List<SuggestionStatus> activeStatuses = List.of(
                SuggestionStatus.DISCUSSING,
                SuggestionStatus.EXPERT_REVIEW,
                SuggestionStatus.PLANNED,
                SuggestionStatus.APPROVED,
                SuggestionStatus.IN_PROGRESS,
                SuggestionStatus.TESTING
        );

        for (String repoDir : suggestionRepoDirs) {
            triggerClaudeMergeForRepo(repoDir, activeStatuses);
        }
    }

    private void triggerClaudeMergeForRepo(String repoDir, List<SuggestionStatus> activeStatuses) {
        // Extract suggestion ID from directory name (e.g., "suggestion-42-repo")
        String dirName = new java.io.File(repoDir).getName();
        String idStr = dirName.replace("suggestion-", "").replace("-repo", "");
        try {
            Long suggestionId = Long.parseLong(idStr);
            Suggestion suggestion = suggestionRepository.findById(suggestionId).orElse(null);
            if (suggestion == null || !activeStatuses.contains(suggestion.getStatus())) {
                return;
            }

            // Fetch the latest from origin (lightweight git operation)
            try {
                claudeService.fetchOrigin(repoDir);
            } catch (Exception e) {
                log.error("Failed to fetch origin for {}: {}", repoDir, e.getMessage());
                return;
            }

            // Detect default branch for the merge prompt
            String defaultBranch;
            try {
                defaultBranch = claudeService.detectDefaultBranchPublic(repoDir);
            } catch (Exception e) {
                log.error("Failed to detect default branch for {}: {}", repoDir, e.getMessage());
                return;
            }

            log.info("Asking Claude to merge origin/{} into suggestion {} repo and assess impact",
                    defaultBranch, suggestionId);

            messagingHelper.addMessage(suggestionId, SenderType.SYSTEM, "System",
                    "The project has been updated. Merging changes and checking impact on this suggestion...");

            suggestion.setCurrentPhase("Merging project updates and checking for impact...");
            suggestionRepository.save(suggestion);
            messagingHelper.broadcastUpdate(suggestion);

            String mergePrompt = buildMergeAndReEvalPrompt(suggestion, defaultBranch);
            String context = buildConversationContext(suggestionId);

            // Use the suggestion's existing session if available, otherwise create a new one
            String sessionId = suggestion.getClaudeSessionId();
            if (sessionId == null || sessionId.isBlank()) {
                sessionId = "suggestion-" + suggestionId;
            }

            claudeService.mergeWithMain(
                    sessionId,
                    repoDir,
                    mergePrompt,
                    context,
                    progress -> messagingHelper.broadcastProgress(suggestionId, progress)
            ).thenAccept(response -> {
                handleMergeResponse(suggestionId, response);
            });

        } catch (NumberFormatException e) {
            log.warn("Could not parse suggestion ID from directory: {}", dirName);
        }
    }

    private void handleMergeResponse(Long suggestionId, String response) {
        Suggestion suggestion = suggestionRepository.findById(suggestionId).orElse(null);
        if (suggestion == null) return;

        // Check if Claude reported a merge failure
        if (response.contains("MERGE_FAILED")) {
            log.error("Claude failed to merge main into suggestion {}: {}", suggestionId, response);
            String displayMessage = extractMessage(response);
            messagingHelper.addMessage(suggestionId, SenderType.SYSTEM, "System",
                    "The project was updated but the changes could not be merged automatically. " +
                    (displayMessage != null ? displayMessage : "This suggestion may need to be re-evaluated."));

            suggestion.setStatus(SuggestionStatus.DISCUSSING);
            suggestion.setCurrentPhase("Conflicting changes — needs attention");
            suggestionRepository.save(suggestion);
            messagingHelper.broadcastUpdate(suggestion);
            return;
        }

        // Check if merge reported no changes
        if (response.contains("NO_CHANGES")) {
            log.info("No changes to merge for suggestion {}", suggestionId);
            suggestion.setCurrentPhase(null);
            suggestionRepository.save(suggestion);
            messagingHelper.broadcastUpdate(suggestion);
            return;
        }

        // Merge succeeded with changes — delegate to the standard AI response handler
        // which handles PLAN_READY and NEEDS_CLARIFICATION
        handleAiResponse(suggestionId, response);
    }

    private String buildMergeAndReEvalPrompt(Suggestion suggestion, String defaultBranch) {
        return "The main repository has been updated with new changes. You need to merge these " +
                "changes into the current suggestion branch and then assess the impact.\n\n" +
                "STEP 1 — MERGE:\n" +
                "Run: git merge origin/" + defaultBranch + "\n" +
                "- If the merge completes cleanly with no changes (already up to date), respond with:\n" +
                "  {\"status\": \"NO_CHANGES\", \"message\": \"Already up to date.\"}\n" +
                "- If the merge completes cleanly WITH changes, proceed to Step 2.\n" +
                "- If there are merge conflicts:\n" +
                "  * Review each conflicting file to understand both sides\n" +
                "  * Resolve conflicts intelligently, preserving both the suggestion's changes " +
                "and the incoming main branch changes where possible\n" +
                "  * Stage the resolved files with git add and complete the merge commit with git commit\n" +
                "  * Then proceed to Step 2\n" +
                "- If you cannot resolve the conflicts, respond with:\n" +
                "  {\"status\": \"MERGE_FAILED\", \"message\": \"<explanation of what went wrong>\"}\n\n" +
                "STEP 2 — ASSESS IMPACT (only if merge brought in changes):\n" +
                "Review the merged changes and determine if they affect this suggestion's plan.\n\n" +
                "Original suggestion:\n" +
                "Title: " + suggestion.getTitle() + "\n" +
                "Description: " + suggestion.getDescription() + "\n" +
                (suggestion.getPlanSummary() != null ?
                        "Current plan: " + suggestion.getPlanSummary() + "\n" : "") +
                "\nDUAL-LEVEL DETAIL RULES:\n" +
                "- The 'plan' field is the LOW-LEVEL technical version for expert reviewers — may reference files, classes, frameworks, and concrete implementation steps.\n" +
                "- The 'planDisplaySummary' field is the HIGH-LEVEL plain-language version for end users — no file names, no technical details.\n" +
                "- The two layers should NOT be identical strings.\n" +
                "- The 'message' field (and any 'questions') MUST stay plain, non-technical.\n\n" +
                "Respond in JSON format:\n" +
                "If the suggestion is NOT affected (plan is still valid):\n" +
                "{\"status\": \"PLAN_READY\", " +
                "\"message\": \"The recent project updates don't affect this suggestion. " +
                "The existing plan is still good.\", " +
                "\"plan\": \"<the existing low-level plan, unchanged>\", " +
                "\"planDisplaySummary\": \"<the existing high-level plain-language summary, unchanged>\"}\n\n" +
                "If the suggestion IS affected and you need clarification:\n" +
                "{\"status\": \"NEEDS_CLARIFICATION\", " +
                "\"message\": \"The project has changed in ways that affect this suggestion.\", " +
                "\"questions\": [\"specific question about how to proceed given the changes\"]}\n\n" +
                "If the suggestion IS affected but you can update the plan:\n" +
                "{\"status\": \"PLAN_READY\", " +
                "\"message\": \"The plan has been updated to account for the recent project changes.\", " +
                "\"plan\": \"<updated low-level technical plan>\", " +
                "\"planDisplaySummary\": \"<updated high-level plain-language summary>\"}\n\n" +
                "IMPORTANT: When status is NEEDS_CLARIFICATION, you MUST include a \"questions\" array. " +
                "When status is PLAN_READY, you MUST include BOTH 'plan' (low-level) and 'planDisplaySummary' (high-level) — they should differ meaningfully.";
    }

}
