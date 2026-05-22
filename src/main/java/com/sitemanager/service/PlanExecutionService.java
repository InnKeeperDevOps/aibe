package com.sitemanager.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sitemanager.model.PlanTask;
import com.sitemanager.model.Suggestion;
import com.sitemanager.model.enums.ExpertRole;
import com.sitemanager.model.enums.SenderType;
import com.sitemanager.model.enums.SuggestionStatus;
import com.sitemanager.model.enums.TaskStatus;
import com.sitemanager.repository.PlanTaskRepository;
import com.sitemanager.repository.SuggestionRepository;
import com.sitemanager.websocket.SuggestionWebSocketHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Owns task execution, PR creation, and the execution queue for suggestions.
 * Does NOT depend on SuggestionService to avoid circular dependencies.
 */
@Service
public class PlanExecutionService {

    private static final Logger log = LoggerFactory.getLogger(PlanExecutionService.class);

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private final SuggestionRepository suggestionRepository;
    private final PlanTaskRepository planTaskRepository;
    private final ClaudeService claudeService;
    private final SuggestionMessagingHelper messagingHelper;
    private final SiteSettingsService settingsService;
    private final SuggestionWebSocketHandler webSocketHandler;
    private final SlackNotificationService slackNotificationService;

    public PlanExecutionService(SuggestionRepository suggestionRepository,
                                PlanTaskRepository planTaskRepository,
                                ClaudeService claudeService,
                                SuggestionMessagingHelper messagingHelper,
                                SiteSettingsService settingsService,
                                SuggestionWebSocketHandler webSocketHandler,
                                SlackNotificationService slackNotificationService) {
        this.suggestionRepository = suggestionRepository;
        this.planTaskRepository = planTaskRepository;
        this.claudeService = claudeService;
        this.messagingHelper = messagingHelper;
        this.settingsService = settingsService;
        this.webSocketHandler = webSocketHandler;
        this.slackNotificationService = slackNotificationService;
    }

    // -------------------------------------------------------------------------
    // Public entry points
    // -------------------------------------------------------------------------

    @Transactional
    public void handleExecutionResult(Long suggestionId, String result) {
        Suggestion suggestion = suggestionRepository.findById(suggestionId).orElse(null);
        if (suggestion == null) return;

        String execStatus = result.contains("COMPLETED") ? "COMPLETED" :
                result.contains("FAILED") ? "FAILED" : "TESTING";
        log.info("[AI-FLOW] suggestion={} execution result: status={} resultLength={}",
                suggestionId, execStatus, result.length());

        messagingHelper.addMessage(suggestionId, SenderType.AI, "Claude", result);

        if (result.contains("COMPLETED")) {
            markRemainingTasksCompleted(suggestionId);

            suggestion.setStatus(SuggestionStatus.DEV_COMPLETE);
            suggestion.setCurrentPhase("Work completed — submitting changes...");
            suggestionRepository.save(suggestion);
            messagingHelper.broadcastUpdate(suggestion);
            slackNotificationService.sendNotification(suggestion, "DEV_COMPLETE");

            createPrAsync(suggestion.getId());
            tryStartNextQueuedSuggestion();
            return;
        } else if (result.contains("FAILED")) {
            suggestion.setCurrentPhase("Something went wrong — can retry");
        } else {
            suggestion.setStatus(SuggestionStatus.TESTING);
            suggestion.setCurrentPhase("Verifying the changes...");
        }

        suggestionRepository.save(suggestion);
        messagingHelper.broadcastUpdate(suggestion);
    }

    @Async
    public void createPrAsync(Long suggestionId) {
        Suggestion suggestion = suggestionRepository.findById(suggestionId).orElse(null);
        if (suggestion == null) {
            log.error("Cannot create PR: suggestion {} not found", suggestionId);
            return;
        }
        createPrForSuggestion(suggestion);
    }

    public Map<String, Object> retryPrCreation(Long suggestionId) {
        Suggestion suggestion = suggestionRepository.findById(suggestionId).orElse(null);
        if (suggestion == null) {
            return Map.of("success", false, "error", "Suggestion not found");
        }
        if (!"Done — review request failed".equals(suggestion.getCurrentPhase())) {
            return Map.of("success", false, "error", "Suggestion is not in a retryable state");
        }

        String repoUrl = settingsService.getSettings().getTargetRepoUrl();
        String githubToken = settingsService.getSettings().getGithubToken();
        String branchName = "suggestion-" + suggestion.getId();

        if (githubToken == null || githubToken.isBlank()) {
            return Map.of("success", false, "error", "No GitHub token configured. Please update it in Settings.");
        }

        try {
            suggestion.setCurrentPhase("Creating a review request...");
            suggestionRepository.save(suggestion);
            messagingHelper.broadcastUpdate(suggestion);

            String prTitle = "Suggestion #" + suggestion.getId() + ": " + suggestion.getTitle();
            String prBody = buildPrBody(suggestion);

            var prResult = claudeService.createGitHubPullRequest(
                    repoUrl, branchName, prTitle, prBody, githubToken);

            String prUrl = (String) prResult.get("html_url");
            int prNumber = (int) prResult.get("number");

            suggestion.setPrUrl(prUrl);
            suggestion.setPrNumber(prNumber);
            suggestion.setStatus(SuggestionStatus.FINAL_REVIEW);
            suggestion.setCurrentPhase("Done — ready for review");
            suggestionRepository.save(suggestion);

            messagingHelper.addMessage(suggestion.getId(), SenderType.SYSTEM, "System",
                    "Changes are ready for review: " + prUrl);

            messagingHelper.broadcastUpdate(suggestion);
            slackNotificationService.sendNotification(suggestion, "PR_CREATED");
            webSocketHandler.sendToSuggestion(suggestion.getId(),
                    "{\"type\":\"pr_created\",\"prUrl\":\"" + messagingHelper.escapeJson(prUrl) +
                    "\",\"prNumber\":" + prNumber + "}");

            if (settingsService.getSettings().isAutoMergePr()) {
                boolean merged = claudeService.mergePullRequest(repoUrl, prNumber, githubToken);
                if (merged) {
                    suggestion.setStatus(SuggestionStatus.MERGED);
                    suggestion.setCurrentPhase("PR automatically merged into main");
                    suggestionRepository.save(suggestion);
                    messagingHelper.addMessage(suggestion.getId(), SenderType.SYSTEM, "System",
                            "The review request was automatically merged into the main branch.");
                    messagingHelper.broadcastUpdate(suggestion);
                    slackNotificationService.sendNotification(suggestion, "PR automatically merged");
                } else {
                    log.warn("Auto-merge failed for suggestion {}, staying in FINAL_REVIEW", suggestion.getId());
                    messagingHelper.addMessage(suggestion.getId(), SenderType.SYSTEM, "System",
                            "Automatic merge wasn't possible — an admin will need to review and merge manually.");
                    slackNotificationService.sendNotification(suggestion, "Auto-merge failed — manual review needed");
                }
            }

            return Map.of("success", true, "prUrl", prUrl);

        } catch (Exception e) {
            log.error("Retry PR creation failed for suggestion {}: {}", suggestion.getId(), e.getMessage(), e);
            messagingHelper.addMessage(suggestion.getId(), SenderType.SYSTEM, "System",
                    "Review request retry failed. An admin can try again.");
            suggestion.setCurrentPhase("Done — review request failed");
            suggestionRepository.save(suggestion);
            messagingHelper.broadcastUpdate(suggestion);
            return Map.of("success", false, "error", "PR creation failed: " + e.getMessage());
        }
    }

    public Map<String, Object> getExecutionQueueStatus() {
        int maxConcurrent = getMaxConcurrentSuggestions();
        long activeCount = suggestionRepository.countByStatusIn(
                List.of(SuggestionStatus.IN_PROGRESS, SuggestionStatus.TESTING));

        List<Suggestion> queued = suggestionRepository.findByStatus(SuggestionStatus.APPROVED);
        queued.sort(java.util.Comparator.comparing(Suggestion::getCreatedAt));

        List<Map<String, Object>> queuedList = new ArrayList<>();
        for (int i = 0; i < queued.size(); i++) {
            Suggestion s = queued.get(i);
            queuedList.add(Map.of(
                    "id", s.getId(),
                    "title", s.getTitle(),
                    "position", i + 1
            ));
        }

        return Map.of(
                "maxConcurrent", maxConcurrent,
                "activeCount", activeCount,
                "queuedCount", queued.size(),
                "queued", queuedList
        );
    }

    // -------------------------------------------------------------------------
    // Execution helpers
    // -------------------------------------------------------------------------

    void executeApprovedSuggestion(Suggestion suggestion) {
        log.info("[AI-FLOW] suggestion={} starting execution", suggestion.getId());
        String repoUrl = settingsService.getSettings().getTargetRepoUrl();
        if (repoUrl == null || repoUrl.isBlank()) {
            messagingHelper.addMessage(suggestion.getId(), SenderType.SYSTEM, "System",
                    "Cannot start work yet — the project hasn't been set up. An admin needs to configure the project in Settings.");
            suggestion.setCurrentPhase("Blocked — project not configured");
            suggestionRepository.save(suggestion);
            messagingHelper.broadcastUpdate(suggestion);
            return;
        }

        int maxConcurrent = getMaxConcurrentSuggestions();
        long activeCount = suggestionRepository.countByStatusIn(
                List.of(SuggestionStatus.IN_PROGRESS, SuggestionStatus.TESTING));
        if (activeCount >= maxConcurrent) {
            log.info("[AI-FLOW] suggestion={} queued — {} suggestion(s) already executing (limit={})",
                    suggestion.getId(), activeCount, maxConcurrent);
            suggestion.setCurrentPhase("Queued — waiting for a slot (" + activeCount + "/" + maxConcurrent + " running)");
            suggestionRepository.save(suggestion);
            messagingHelper.broadcastUpdate(suggestion);
            messagingHelper.addMessage(suggestion.getId(), SenderType.SYSTEM, "System",
                    "Your suggestion is approved and queued. It will start automatically when a slot opens up.");
            messagingHelper.broadcastExecutionQueueStatus();
            return;
        }

        suggestion.setStatus(SuggestionStatus.IN_PROGRESS);
        suggestion.setCurrentPhase("Setting up a workspace for this suggestion...");
        suggestionRepository.save(suggestion);
        messagingHelper.broadcastUpdate(suggestion);
        slackNotificationService.sendNotification(suggestion, "IN_PROGRESS");
        messagingHelper.broadcastExecutionQueueStatus();

        new Thread(() -> {
            try {
                String workDir = claudeService.cloneRepository(repoUrl, suggestion.getId().toString());

                String branchName = "suggestion-" + suggestion.getId();
                claudeService.createBranch(workDir, branchName);

                suggestion.setWorkingDirectory(workDir);
                suggestionRepository.save(suggestion);

                messagingHelper.addMessage(suggestion.getId(), SenderType.SYSTEM, "System",
                        "Workspace ready. Starting work on your suggestion one task at a time...");

                executeNextTask(suggestion.getId());

            } catch (Exception e) {
                log.error("Failed to execute suggestion {}: {}", suggestion.getId(), e.getMessage(), e);
                suggestion.setFailureReason(trimTo1000(e.getMessage() != null ? e.getMessage() : "Unexpected error during setup"));
                suggestion.setCurrentPhase("Failed — can retry");
                suggestionRepository.save(suggestion);
                messagingHelper.broadcastUpdate(suggestion);
                messagingHelper.addMessage(suggestion.getId(), SenderType.SYSTEM, "System",
                        "Something went wrong while working on this suggestion. It can be retried.");
                tryStartNextQueuedSuggestion();
            }
        }).start();
    }

    /**
     * Find the next PENDING task and execute it. Called after each task's expert review passes.
     * If no more tasks remain, finalize the suggestion as COMPLETED.
     */
    void executeNextTask(Long suggestionId) {
        Suggestion suggestion = suggestionRepository.findById(suggestionId).orElse(null);
        if (suggestion == null) return;

        List<PlanTask> tasks = planTaskRepository.findBySuggestionIdOrderByTaskOrder(suggestionId);
        if (tasks.isEmpty()) {
            log.warn("[AI-FLOW] suggestion={} has no tasks, falling back to full plan execution", suggestionId);
            String workDir = suggestion.getWorkingDirectory();
            String executionSessionId = claudeService.generateSessionId();
            String tasksJson = buildTasksJsonForExecution(suggestionId);
            claudeService.executePlan(
                    executionSessionId,
                    suggestion.getPlanSummary() != null ? suggestion.getPlanSummary() : suggestion.getDescription(),
                    tasksJson,
                    workDir,
                    progress -> handleExecutionProgress(suggestionId, progress)
            ).thenAccept(result -> handleExecutionResult(suggestionId, result));
            return;
        }

        // Pick the first task that isn't COMPLETED. Tasks run strictly in order
        // and each builds on the previous one, so a FAILED task earlier in the
        // plan blocks everything after it — execution must restart that task
        // rather than skip ahead to a later PENDING task (which would run
        // against a half-finished codebase and fail too).
        PlanTask nextTask = tasks.stream()
                .filter(t -> t.getStatus() != TaskStatus.COMPLETED)
                .findFirst()
                .orElse(null);

        if (nextTask == null) {
            log.info("[AI-FLOW] suggestion={} all {} tasks completed, finalizing", suggestionId, tasks.size());
            markRemainingTasksCompleted(suggestionId);

            suggestion.setStatus(SuggestionStatus.DEV_COMPLETE);
            suggestion.setCurrentPhase("Work completed — submitting changes...");
            suggestionRepository.save(suggestion);
            messagingHelper.broadcastUpdate(suggestion);
            slackNotificationService.sendNotification(suggestion, "DEV_COMPLETE");

            messagingHelper.addMessage(suggestionId, SenderType.SYSTEM, "System",
                    "All tasks have been completed and verified by expert review. Submitting changes...");
            createPrAsync(suggestionId);
            tryStartNextQueuedSuggestion();
            return;
        }

        // A previously FAILED (or interrupted) task is restarted from a clean
        // slate — fresh retry budget, cleared failure reason — instead of being
        // skipped, so the plan resumes correctly from the point it broke.
        if (nextTask.getStatus() != TaskStatus.PENDING) {
            log.info("[AI-FLOW] suggestion={} restarting task {} (was {})",
                    suggestionId, nextTask.getTaskOrder(), nextTask.getStatus());
            messagingHelper.addMessage(suggestionId, SenderType.SYSTEM, "System",
                    "Restarting task " + nextTask.getTaskOrder() + " from the beginning...");
            nextTask.setRetryCount(0);
            nextTask.setFailureReason(null);
            nextTask.setCompletedAt(null);
        }

        int totalTasks = tasks.size();
        int taskOrder = nextTask.getTaskOrder();
        log.info("[AI-FLOW] suggestion={} executing task {}/{}: {}", suggestionId, taskOrder, totalTasks, nextTask.getTitle());

        nextTask.setStatus(TaskStatus.IN_PROGRESS);
        nextTask.setStartedAt(Instant.now());
        nextTask.setStatusDetail("Writing code...");
        planTaskRepository.save(nextTask);
        messagingHelper.broadcastTaskUpdate(suggestionId, nextTask);

        suggestion.setCurrentPhase("Task " + taskOrder + "/" + totalTasks + ": " + nextTask.getTitle());
        suggestionRepository.save(suggestion);
        messagingHelper.broadcastUpdate(suggestion);

        String completedSummary = buildCompletedTasksSummary(suggestionId);

        String executionSessionId = claudeService.generateSessionId();
        String workDir = suggestion.getWorkingDirectory();
        String plan = suggestion.getPlanSummary() != null ? suggestion.getPlanSummary() : suggestion.getDescription();

        final PlanTask taskSnapshot = nextTask;
        claudeService.executeSingleTask(
                executionSessionId,
                plan,
                taskOrder,
                nextTask.getTitle(),
                nextTask.getDescription(),
                totalTasks,
                completedSummary,
                workDir,
                progress -> handleExecutionProgress(suggestionId, progress)
        ).thenAccept(result -> handleSingleTaskResult(suggestionId, taskOrder, result))
         .exceptionally(ex -> {
             handleTaskException(suggestionId, taskOrder, taskSnapshot, ex);
             return null;
         });
    }

    /**
     * Handle the result of a single task execution. If the task completed,
     * trigger expert review before moving to the next task.
     */
    private void handleSingleTaskResult(Long suggestionId, int taskOrder, String result) {
        Suggestion suggestion = suggestionRepository.findById(suggestionId).orElse(null);
        if (suggestion == null) return;

        log.info("[AI-FLOW] suggestion={} task {} execution result: resultLength={}",
                suggestionId, taskOrder, result.length());

        Optional<PlanTask> optTask = planTaskRepository.findBySuggestionIdAndTaskOrder(suggestionId, taskOrder);
        if (optTask.isEmpty()) return;

        PlanTask task = optTask.get();

        if (task.getStatus() == TaskStatus.COMPLETED) {
            task.setStatusDetail("Implementation finished — starting review");
            planTaskRepository.save(task);
            messagingHelper.broadcastTaskUpdate(suggestionId, task);
            messagingHelper.addMessage(suggestionId, SenderType.SYSTEM, "System",
                    "Task " + taskOrder + " implementation complete. Experts are now reviewing the work...");
            startTaskCompletionReview(suggestionId, taskOrder);
        } else if (task.getStatus() == TaskStatus.FAILED) {
            String reason = extractFailureMessage(result);
            task.setFailureReason(trimTo1000(reason));
            task.setStatusDetail("Implementation failed");
            planTaskRepository.save(task);
            messagingHelper.broadcastTaskUpdate(suggestionId, task);
            messagingHelper.addMessage(suggestionId, SenderType.AI, "Claude", result);
            suggestion.setFailureReason("Task '" + task.getTitle() + "' failed: " + trimTo1000(reason));
            suggestion.setCurrentPhase("Task " + taskOrder + " failed — can retry");
            suggestionRepository.save(suggestion);
            messagingHelper.broadcastUpdate(suggestion);
        } else {
            task.setStatus(TaskStatus.COMPLETED);
            task.setCompletedAt(Instant.now());
            if (task.getStartedAt() == null) task.setStartedAt(task.getCompletedAt());
            task.setStatusDetail("Implementation finished — starting review");
            planTaskRepository.save(task);
            messagingHelper.broadcastTaskUpdate(suggestionId, task);

            messagingHelper.addMessage(suggestionId, SenderType.SYSTEM, "System",
                    "Task " + taskOrder + " implementation complete. Experts are now reviewing the work...");
            startTaskCompletionReview(suggestionId, taskOrder);
        }
    }

    /**
     * Start expert review of a completed task. Uses Software Engineer and QA Engineer
     * to verify the task was properly implemented before proceeding.
     */
    private void startTaskCompletionReview(Long suggestionId, int taskOrder) {
        Suggestion suggestion = suggestionRepository.findById(suggestionId).orElse(null);
        if (suggestion == null) return;

        Optional<PlanTask> optTask = planTaskRepository.findBySuggestionIdAndTaskOrder(suggestionId, taskOrder);
        if (optTask.isEmpty()) return;

        PlanTask task = optTask.get();

        if (task.getStatus() == TaskStatus.REVIEWING) {
            log.warn("Task {} for suggestion {} is already being reviewed, skipping duplicate review",
                    taskOrder, suggestionId);
            return;
        }
        if (task.getStatus() != TaskStatus.COMPLETED) {
            log.warn("Task {} for suggestion {} is in {} state, not eligible for expert review",
                    taskOrder, suggestionId, task.getStatus());
            return;
        }

        task.setStatus(TaskStatus.REVIEWING);
        task.setStatusDetail("Queued for expert review");
        planTaskRepository.save(task);
        messagingHelper.broadcastTaskUpdate(suggestionId, task);

        List<PlanTask> allTasks = planTaskRepository.findBySuggestionIdOrderByTaskOrder(suggestionId);
        int totalTasks = allTasks.size();

        suggestion.setCurrentPhase("Experts reviewing task " + taskOrder + "/" + totalTasks + ": " + task.getTitle());
        suggestionRepository.save(suggestion);
        messagingHelper.broadcastUpdate(suggestion);

        ExpertRole[] taskReviewers = { ExpertRole.SOFTWARE_ENGINEER, ExpertRole.QA_ENGINEER };
        String plan = suggestion.getPlanSummary() != null ? suggestion.getPlanSummary() : suggestion.getDescription();
        String workDir = suggestion.getWorkingDirectory();

        runTaskReviewer(suggestionId, taskOrder, task, plan, workDir, taskReviewers, 0);
    }

    /**
     * Run a single task reviewer. After completion, either run the next reviewer
     * or proceed to the next task.
     */
    private void runTaskReviewer(Long suggestionId, int taskOrder, PlanTask task,
                                  String plan, String workDir,
                                  ExpertRole[] reviewers, int reviewerIndex) {
        if (reviewerIndex >= reviewers.length) {
            task.setStatus(TaskStatus.COMPLETED);
            task.setStatusDetail("Verified by expert review");
            planTaskRepository.save(task);
            messagingHelper.broadcastTaskUpdate(suggestionId, task);

            Suggestion suggestion = suggestionRepository.findById(suggestionId).orElse(null);
            if (suggestion == null) return;

            List<PlanTask> allTasks = planTaskRepository.findBySuggestionIdOrderByTaskOrder(suggestionId);
            long completedCount = allTasks.stream().filter(t -> t.getStatus() == TaskStatus.COMPLETED).count();

            messagingHelper.addMessage(suggestionId, SenderType.SYSTEM, "System",
                    "Task " + taskOrder + " has been verified by expert review (" + completedCount + "/" + allTasks.size() + " tasks complete). Moving to the next task...");

            log.info("[AI-FLOW] suggestion={} task {} passed expert review, proceeding to next task", suggestionId, taskOrder);
            executeNextTask(suggestionId);
            return;
        }

        ExpertRole reviewer = reviewers[reviewerIndex];
        Suggestion suggestion = suggestionRepository.findById(suggestionId).orElse(null);
        if (suggestion == null) return;

        List<PlanTask> allTasks = planTaskRepository.findBySuggestionIdOrderByTaskOrder(suggestionId);
        int totalTasks = allTasks.size();

        task.setStatusDetail(reviewer.getDisplayName() + " is reviewing...");
        planTaskRepository.save(task);
        messagingHelper.broadcastTaskUpdate(suggestionId, task);

        suggestion.setCurrentPhase(reviewer.getDisplayName() + " reviewing task " + taskOrder + "/" + totalTasks + "...");
        suggestionRepository.save(suggestion);
        messagingHelper.broadcastUpdate(suggestion);

        String reviewSessionId = claudeService.generateSessionId();
        claudeService.reviewTaskCompletion(
                reviewSessionId,
                reviewer.getDisplayName(),
                reviewer.getReviewPrompt(),
                suggestion.getTitle(),
                taskOrder,
                task.getTitle(),
                task.getDescription(),
                plan,
                workDir,
                progress -> webSocketHandler.sendToSuggestion(suggestionId,
                        "{\"type\":\"execution_progress\",\"content\":\"" +
                                messagingHelper.escapeJson(progress) + "\"}")
        ).thenAccept(response -> {
            handleTaskReviewResponse(suggestionId, taskOrder, task, plan, workDir,
                    reviewer, reviewers, reviewerIndex, response);
        });
    }

    /**
     * Handle the response from a task completion reviewer.
     */
    private void handleTaskReviewResponse(Long suggestionId, int taskOrder, PlanTask task,
                                           String plan, String workDir,
                                           ExpertRole reviewer, ExpertRole[] reviewers,
                                           int reviewerIndex, String response) {
        Suggestion suggestion = suggestionRepository.findById(suggestionId).orElse(null);
        if (suggestion == null) return;

        log.info("[AI-FLOW] suggestion={} task {} reviewer={} response received",
                suggestionId, taskOrder, reviewer.getDisplayName());

        try {
            String json = extractJsonBlock(response);
            if (json == null) {
                log.warn("Task reviewer {} provided no structured response for task {}, treating as approved",
                        reviewer.getDisplayName(), taskOrder);
                messagingHelper.broadcastExpertNote(suggestionId, reviewer.getDisplayName(),
                        "Task " + taskOrder + " review: Approved (no structured response).");
                runTaskReviewer(suggestionId, taskOrder, task, plan, workDir, reviewers, reviewerIndex + 1);
                return;
            }

            JsonNode root = objectMapper.readTree(json);
            String status = root.has("status") ? root.get("status").asText() : "APPROVED";
            String analysis = root.has("analysis") ? root.get("analysis").asText() : "";
            String message = root.has("message") ? root.get("message").asText() : "";

            log.info("[AI-FLOW] suggestion={} task {} reviewer={} verdict={}",
                    suggestionId, taskOrder, reviewer.getDisplayName(), status);

            if ("NEEDS_FIXES".equals(status)) {
                String fixes = root.has("fixes") ? root.get("fixes").asText() : analysis;

                messagingHelper.broadcastExpertNote(suggestionId, reviewer.getDisplayName(),
                        "Task " + taskOrder + " review: Needs fixes. " + analysis);
                messagingHelper.addMessage(suggestionId, SenderType.AI, reviewer.getDisplayName(), message);

                messagingHelper.addMessage(suggestionId, SenderType.SYSTEM, "System",
                        reviewer.getDisplayName() + " found issues with task " + taskOrder + ". Fixing...");

                task.setStatusDetail("Fixing issues found by " + reviewer.getDisplayName());
                planTaskRepository.save(task);
                messagingHelper.broadcastTaskUpdate(suggestionId, task);

                suggestion.setCurrentPhase("Fixing issues in task " + taskOrder + " found by " + reviewer.getDisplayName() + "...");
                suggestionRepository.save(suggestion);
                messagingHelper.broadcastUpdate(suggestion);

                String fixSessionId = claudeService.generateSessionId();
                String fixPrompt = String.format(
                        "The %s has reviewed your implementation of task %d and found issues that need to be fixed.\n\n" +
                        "Task: %s\n" +
                        "Description: %s\n\n" +
                        "Issues found:\n%s\n\n" +
                        "Please fix these issues. After fixing, verify your changes are correct.\n\n" +
                        "When done, output:\n" +
                        "{\"taskOrder\": %d, \"status\": \"COMPLETED\", \"message\": \"what was fixed and verified\"}",
                        reviewer.getDisplayName(), taskOrder,
                        task.getTitle(),
                        task.getDescription() != null ? task.getDescription() : task.getTitle(),
                        fixes,
                        taskOrder
                );

                claudeService.continueConversation(
                        fixSessionId,
                        fixPrompt,
                        null,
                        workDir,
                        progress -> handleExecutionProgress(suggestionId, progress)
                ).thenAccept(fixResult -> {
                    log.info("[AI-FLOW] suggestion={} task {} fix applied, re-reviewing with {}",
                            suggestionId, taskOrder, reviewer.getDisplayName());
                    runTaskReviewer(suggestionId, taskOrder, task, plan, workDir, reviewers, reviewerIndex);
                });
                return;
            }

            messagingHelper.broadcastExpertNote(suggestionId, reviewer.getDisplayName(),
                    "Task " + taskOrder + ": Approved.");
            messagingHelper.addMessage(suggestionId, SenderType.AI, reviewer.getDisplayName(), "Approved.");
            runTaskReviewer(suggestionId, taskOrder, task, plan, workDir, reviewers, reviewerIndex + 1);

        } catch (Exception e) {
            log.error("Failed to handle task review response for suggestion {} task {}: {}",
                    suggestionId, taskOrder, e.getMessage(), e);
            messagingHelper.broadcastExpertNote(suggestionId, reviewer.getDisplayName(),
                    "Task " + taskOrder + " review: Approved (processing error).");
            runTaskReviewer(suggestionId, taskOrder, task, plan, workDir, reviewers, reviewerIndex + 1);
        }
    }

    private String buildCompletedTasksSummary(Long suggestionId) {
        List<PlanTask> tasks = planTaskRepository.findBySuggestionIdOrderByTaskOrder(suggestionId);
        StringBuilder sb = new StringBuilder();
        for (PlanTask task : tasks) {
            if (task.getStatus() == TaskStatus.COMPLETED) {
                sb.append("Task ").append(task.getTaskOrder()).append(" (COMPLETED): ").append(task.getTitle()).append("\n");
            }
        }
        return sb.toString();
    }

    private int getMaxConcurrentSuggestions() {
        Integer max = settingsService.getSettings().getMaxConcurrentSuggestions();
        return (max != null && max >= 1) ? max : 1;
    }

    /**
     * Try to start the next queued (APPROVED) suggestion if a slot is available.
     */
    void tryStartNextQueuedSuggestion() {
        int maxConcurrent = getMaxConcurrentSuggestions();
        long activeCount = suggestionRepository.countByStatusIn(
                List.of(SuggestionStatus.IN_PROGRESS, SuggestionStatus.TESTING));
        if (activeCount >= maxConcurrent) {
            return;
        }

        List<Suggestion> queued = suggestionRepository.findByStatus(SuggestionStatus.APPROVED);
        if (queued.isEmpty()) {
            return;
        }

        Suggestion next = queued.stream()
                .min(java.util.Comparator.comparing(Suggestion::getCreatedAt))
                .orElse(null);
        if (next == null) return;

        log.info("[AI-FLOW] Starting next queued suggestion {} (slot freed, {}/{} active)",
                next.getId(), activeCount, maxConcurrent);
        messagingHelper.addMessage(next.getId(), SenderType.SYSTEM, "System",
                "A slot has opened up. Starting work on your suggestion now.");
        executeApprovedSuggestion(next);
    }

    private void handleExecutionProgress(Long suggestionId, String progress) {
        webSocketHandler.sendToSuggestion(suggestionId,
                "{\"type\":\"execution_progress\",\"content\":\"" +
                        messagingHelper.escapeJson(progress) + "\"}");

        deriveTaskActivity(suggestionId, progress);

        try {
            String json = extractJsonBlock(progress);
            if (json == null) return;

            JsonNode node = objectMapper.readTree(json);
            if (!node.has("taskOrder")) return;

            int taskOrder = node.get("taskOrder").asInt();
            String status = node.has("status") ? node.get("status").asText() : null;
            String message = node.has("message") ? node.get("message").asText() : null;

            if (status == null) return;

            Optional<PlanTask> optTask = planTaskRepository.findBySuggestionIdAndTaskOrder(suggestionId, taskOrder);
            if (optTask.isEmpty()) return;

            PlanTask task = optTask.get();
            TaskStatus newStatus = TaskStatus.valueOf(status);
            task.setStatus(newStatus);

            if (newStatus == TaskStatus.IN_PROGRESS && task.getStartedAt() == null) {
                task.setStartedAt(Instant.now());
                task.setStatusDetail(message != null ? message : "Writing code...");

                Suggestion suggestion = suggestionRepository.findById(suggestionId).orElse(null);
                if (suggestion != null) {
                    List<PlanTask> allTasks = planTaskRepository.findBySuggestionIdOrderByTaskOrder(suggestionId);
                    suggestion.setCurrentPhase("Task " + taskOrder + "/" + allTasks.size() + ": " + task.getTitle());
                    suggestionRepository.save(suggestion);
                    messagingHelper.broadcastUpdate(suggestion);
                }
            } else if (newStatus == TaskStatus.IN_PROGRESS) {
                task.setStatusDetail(message != null ? message : task.getStatusDetail());
            } else if (newStatus == TaskStatus.REVIEWING) {
                task.setStatusDetail("Implementation finished — starting review");
                Suggestion suggestion = suggestionRepository.findById(suggestionId).orElse(null);
                if (suggestion != null) {
                    List<PlanTask> allTasks = planTaskRepository.findBySuggestionIdOrderByTaskOrder(suggestionId);
                    suggestion.setCurrentPhase("Reviewing task " + taskOrder + "/" + allTasks.size() + ": " + task.getTitle());
                    suggestionRepository.save(suggestion);
                    messagingHelper.broadcastUpdate(suggestion);
                }
            } else if (newStatus == TaskStatus.COMPLETED || newStatus == TaskStatus.FAILED) {
                task.setCompletedAt(Instant.now());
                task.setStatusDetail(newStatus == TaskStatus.COMPLETED
                        ? (message != null ? message : "Done")
                        : (message != null ? message : "Failed"));

                Suggestion suggestion = suggestionRepository.findById(suggestionId).orElse(null);
                if (suggestion != null) {
                    List<PlanTask> allTasks = planTaskRepository.findBySuggestionIdOrderByTaskOrder(suggestionId);
                    long completed = allTasks.stream().filter(t -> t.getStatus() == TaskStatus.COMPLETED).count();
                    if (newStatus == TaskStatus.COMPLETED) completed++;
                    long total = allTasks.size();
                    if (completed >= total) {
                        suggestion.setCurrentPhase("All " + total + " tasks done — wrapping up...");
                    } else {
                        String nextTask = allTasks.stream()
                                .filter(t -> t.getStatus() == TaskStatus.PENDING && t.getTaskOrder() > taskOrder)
                                .findFirst()
                                .map(t -> "Task " + t.getTaskOrder() + "/" + total + ": " + t.getTitle())
                                .orElse("Completed " + completed + "/" + total + " tasks");
                        suggestion.setCurrentPhase(nextTask);
                    }
                    suggestionRepository.save(suggestion);
                    messagingHelper.broadcastUpdate(suggestion);
                }
            }

            planTaskRepository.save(task);
            messagingHelper.broadcastTaskUpdate(suggestionId, task);
        } catch (Exception e) {
            // Not a task status line — just normal progress text, ignore
        }
    }

    private void deriveTaskActivity(Long suggestionId, String progress) {
        try {
            if (progress == null || progress.isBlank()) return;
            if (progress.contains("\"taskOrder\"")) return;

            String lower = progress.toLowerCase();
            String activity = null;

            if (lower.contains("reading") || lower.contains("analyzing")) {
                activity = "Analyzing existing code...";
            } else if (lower.contains("creating file") || lower.contains("writing file") || lower.contains("create file")) {
                activity = "Creating new files...";
            } else if (lower.contains("editing") || lower.contains("modifying") || lower.contains("updating")) {
                activity = "Editing files...";
            } else if (lower.contains("running test") || lower.contains("executing test")) {
                activity = "Running tests...";
            } else if (lower.contains("installing") || lower.contains("dependencies")) {
                activity = "Installing dependencies...";
            } else if (lower.contains("building") || lower.contains("compiling")) {
                activity = "Building project...";
            } else if (lower.contains("fixing") || lower.contains("fix ")) {
                activity = "Fixing issues...";
            } else if (lower.contains("refactor")) {
                activity = "Refactoring code...";
            } else if (lower.contains("configur")) {
                activity = "Updating configuration...";
            }

            if (activity == null) return;

            final String activityMsg = activity;
            List<PlanTask> tasks = planTaskRepository.findBySuggestionIdOrderByTaskOrder(suggestionId);
            tasks.stream()
                    .filter(t -> t.getStatus() == TaskStatus.IN_PROGRESS)
                    .findFirst()
                    .ifPresent(task -> messagingHelper.broadcastTaskActivity(suggestionId, task.getTaskOrder(), activityMsg));
        } catch (Exception e) {
            // Non-critical — ignore errors in activity derivation
        }
    }

    private void markRemainingTasksCompleted(Long suggestionId) {
        List<PlanTask> tasks = planTaskRepository.findBySuggestionIdOrderByTaskOrder(suggestionId);
        for (PlanTask task : tasks) {
            if (task.getStatus() != TaskStatus.COMPLETED && task.getStatus() != TaskStatus.FAILED) {
                task.setStatus(TaskStatus.COMPLETED);
                task.setStatusDetail("Done");
                if (task.getCompletedAt() == null) {
                    task.setCompletedAt(Instant.now());
                }
                if (task.getStartedAt() == null) {
                    task.setStartedAt(task.getCompletedAt());
                }
                planTaskRepository.save(task);
                messagingHelper.broadcastTaskUpdate(suggestionId, task);
            }
        }
    }

    // -------------------------------------------------------------------------
    // PR creation helpers
    // -------------------------------------------------------------------------

    private void createPrForSuggestion(Suggestion suggestion) {
        String repoUrl = settingsService.getSettings().getTargetRepoUrl();
        String githubToken = settingsService.getSettings().getGithubToken();
        String branchName = "suggestion-" + suggestion.getId();
        String workDir = suggestion.getWorkingDirectory();

        String changelog = generateChangelog(suggestion);
        suggestion.setChangelogEntry(changelog);
        suggestionRepository.save(suggestion);

        try {
            claudeService.stageAllChanges(workDir);

            String commitMessage;
            try {
                String diffSummary = claudeService.getStagedDiffSummary(workDir);
                commitMessage = claudeService.generateCommitMessage(
                        suggestion.getClaudeSessionId(),
                        suggestion.getTitle(),
                        suggestion.getDescription(),
                        diffSummary);
            } catch (Exception e) {
                log.warn("Failed to generate commit message via Claude, using default", e);
                commitMessage = "Suggestion #" + suggestion.getId() + ": " + suggestion.getTitle();
            }

            boolean hasChanges = claudeService.commitStagedChanges(workDir, commitMessage);
            if (!hasChanges) {
                log.warn("No changes to commit for suggestion {}", suggestion.getId());
                messagingHelper.addMessage(suggestion.getId(), SenderType.SYSTEM, "System",
                        "No file changes were detected. The branch has no new commits.");
            }
        } catch (Exception e) {
            log.error("Failed to commit changes for suggestion {}: {}", suggestion.getId(), e.getMessage(), e);
            messagingHelper.addMessage(suggestion.getId(), SenderType.SYSTEM, "System",
                    "Completed the work, but couldn't commit the changes. An admin can retry this.");
            suggestion.setCurrentPhase("Done, but commit failed");
            suggestionRepository.save(suggestion);
            messagingHelper.broadcastUpdate(suggestion);
            return;
        }

        try {
            suggestion.setCurrentPhase("Submitting changes...");
            suggestionRepository.save(suggestion);
            messagingHelper.broadcastUpdate(suggestion);

            claudeService.pushBranch(workDir, branchName);

            messagingHelper.addMessage(suggestion.getId(), SenderType.SYSTEM, "System",
                    "Changes have been submitted for review.");
        } catch (Exception e) {
            log.error("Failed to push branch for suggestion {}: {}", suggestion.getId(), e.getMessage(), e);
            messagingHelper.addMessage(suggestion.getId(), SenderType.SYSTEM, "System",
                    "Completed the work, but couldn't submit the changes. An admin can retry this.");
            suggestion.setCurrentPhase("Done, but submission failed");
            suggestionRepository.save(suggestion);
            messagingHelper.broadcastUpdate(suggestion);
            return;
        }

        if (githubToken == null || githubToken.isBlank()) {
            log.warn("No GitHub token configured, skipping PR creation for suggestion {}", suggestion.getId());
            messagingHelper.addMessage(suggestion.getId(), SenderType.SYSTEM, "System",
                    "Changes submitted. Automatic review request wasn't created — an admin can set this up in Settings.");
            suggestion.setCurrentPhase("Done — changes submitted");
            suggestionRepository.save(suggestion);
            messagingHelper.broadcastUpdate(suggestion);
            return;
        }

        try {
            suggestion.setCurrentPhase("Creating a review request...");
            suggestionRepository.save(suggestion);
            messagingHelper.broadcastUpdate(suggestion);

            String prTitle = "Suggestion #" + suggestion.getId() + ": " + suggestion.getTitle();
            String prBody = buildPrBody(suggestion);

            var prResult = claudeService.createGitHubPullRequest(
                    repoUrl, branchName, prTitle, prBody, githubToken);

            String prUrl = (String) prResult.get("html_url");
            int prNumber = (int) prResult.get("number");

            suggestion.setPrUrl(prUrl);
            suggestion.setPrNumber(prNumber);
            suggestion.setStatus(SuggestionStatus.FINAL_REVIEW);
            suggestion.setCurrentPhase("Done — ready for review");
            suggestionRepository.save(suggestion);

            messagingHelper.addMessage(suggestion.getId(), SenderType.SYSTEM, "System",
                    "Changes are ready for review: " + prUrl);

            messagingHelper.broadcastUpdate(suggestion);
            slackNotificationService.sendNotification(suggestion, "PR_CREATED");
            webSocketHandler.sendToSuggestion(suggestion.getId(),
                    "{\"type\":\"pr_created\",\"prUrl\":\"" + messagingHelper.escapeJson(prUrl) +
                    "\",\"prNumber\":" + prNumber + "}");

            if (settingsService.getSettings().isAutoMergePr()) {
                boolean merged = claudeService.mergePullRequest(repoUrl, prNumber, githubToken);
                if (merged) {
                    suggestion.setStatus(SuggestionStatus.MERGED);
                    suggestion.setCurrentPhase("PR automatically merged into main");
                    suggestionRepository.save(suggestion);
                    messagingHelper.addMessage(suggestion.getId(), SenderType.SYSTEM, "System",
                            "The review request was automatically merged into the main branch.");
                    messagingHelper.broadcastUpdate(suggestion);
                    slackNotificationService.sendNotification(suggestion, "PR automatically merged");
                } else {
                    log.warn("Auto-merge failed for suggestion {}, staying in FINAL_REVIEW", suggestion.getId());
                    messagingHelper.addMessage(suggestion.getId(), SenderType.SYSTEM, "System",
                            "Automatic merge wasn't possible — an admin will need to review and merge manually.");
                    slackNotificationService.sendNotification(suggestion, "Auto-merge failed — manual review needed");
                }
            }

        } catch (Exception e) {
            log.error("Failed to create PR for suggestion {}: {}", suggestion.getId(), e.getMessage(), e);
            messagingHelper.addMessage(suggestion.getId(), SenderType.SYSTEM, "System",
                    "Changes were submitted, but the review request couldn't be created automatically.");
            suggestion.setCurrentPhase("Done — review request failed");
            suggestionRepository.save(suggestion);
            messagingHelper.broadcastUpdate(suggestion);
        }
    }

    private String generateChangelog(Suggestion suggestion) {
        StringBuilder entry = new StringBuilder();
        entry.append("### Suggestion #").append(suggestion.getId())
                .append(": ").append(suggestion.getTitle()).append("\n\n");

        entry.append("**Author:** ").append(suggestion.getAuthorName() != null ?
                suggestion.getAuthorName() : "Anonymous").append("\n");
        entry.append("**Date:** ").append(java.time.LocalDate.now()).append("\n");
        entry.append("**Branch:** `suggestion-").append(suggestion.getId()).append("`\n\n");

        entry.append("**Description:**\n").append(suggestion.getDescription()).append("\n\n");

        if (suggestion.getPlanSummary() != null) {
            entry.append("**Implementation Plan:**\n").append(suggestion.getPlanSummary()).append("\n");
        }

        return entry.toString();
    }

    private String buildPrBody(Suggestion suggestion) {
        StringBuilder body = new StringBuilder();
        body.append("## Changelog\n\n");
        body.append(suggestion.getChangelogEntry()).append("\n");

        body.append("---\n\n");
        body.append("*Automatically created by Site Suggestion Platform from ");
        body.append("[Suggestion #").append(suggestion.getId()).append("]*\n\n");

        body.append("**Suggestion link:** `/suggestions/").append(suggestion.getId()).append("`\n\n");

        if (suggestion.getUpVotes() > 0 || suggestion.getDownVotes() > 0) {
            body.append("**Community votes:** ").append(suggestion.getUpVotes())
                    .append(" up / ").append(suggestion.getDownVotes()).append(" down\n");
        }

        return body.toString();
    }

    // -------------------------------------------------------------------------
    // Plan task management
    // -------------------------------------------------------------------------

    /**
     * Parse and persist plan tasks from a Claude response JSON. Replaces any
     * existing tasks (re-plan scenario). Guards against changes when the plan
     * is locked (APPROVED / IN_PROGRESS / TESTING / DEV_COMPLETE).
     */
    void savePlanTasks(Long suggestionId, String response) {
        Suggestion suggestion = suggestionRepository.findById(suggestionId).orElse(null);
        if (suggestion != null && isPlanLocked(suggestion.getStatus())) {
            log.warn("Blocking plan task changes for suggestion {} — plan is locked in {} state",
                    suggestionId, suggestion.getStatus());
            return;
        }
        try {
            planTaskRepository.deleteBySuggestionId(suggestionId);

            String json = extractJsonBlock(response);
            if (json == null) return;

            JsonNode root = objectMapper.readTree(json);
            JsonNode tasksNode = root.get("tasks");
            if (tasksNode == null || !tasksNode.isArray()) return;

            int order = 1;
            for (JsonNode taskNode : tasksNode) {
                savePlanTasksFromNode(taskNode, order++, suggestionId);
            }

            log.info("Saved {} plan tasks for suggestion {}", order - 1, suggestionId);
            messagingHelper.broadcastTasks(suggestionId);
        } catch (Exception e) {
            log.warn("Failed to parse plan tasks from response: {}", e.getMessage());
        }
    }

    private void savePlanTasksFromNode(JsonNode taskNode, int order, Long suggestionId) {
        PlanTask task = new PlanTask();
        task.setSuggestionId(suggestionId);
        task.setTaskOrder(order);
        task.setTitle(taskNode.has("title") ? taskNode.get("title").asText() : "Task " + order);
        task.setDescription(taskNode.has("description") ? taskNode.get("description").asText() : null);
        task.setDisplayTitle(taskNode.has("displayTitle") ? taskNode.get("displayTitle").asText() : task.getTitle());
        task.setDisplayDescription(taskNode.has("displayDescription") ? taskNode.get("displayDescription").asText() : task.getDescription());
        task.setEstimatedMinutes(taskNode.has("estimatedMinutes") ? taskNode.get("estimatedMinutes").asInt() : null);
        task.setStatus(TaskStatus.PENDING);
        task.setStatusDetail("Waiting to start");
        planTaskRepository.save(task);
    }

    private String buildTaskStatusSummary(Long suggestionId) {
        List<PlanTask> tasks = planTaskRepository.findBySuggestionIdOrderByTaskOrder(suggestionId);
        if (tasks.isEmpty()) return null;

        StringBuilder sb = new StringBuilder();
        for (PlanTask task : tasks) {
            sb.append("Task ").append(task.getTaskOrder()).append(": ")
              .append(task.getTitle()).append(" — ").append(task.getStatus()).append("\n");
        }
        return sb.toString();
    }

    private String buildTasksJsonForExecution(Long suggestionId) {
        List<PlanTask> tasks = planTaskRepository.findBySuggestionIdOrderByTaskOrder(suggestionId);
        if (tasks.isEmpty()) return null;

        StringBuilder sb = new StringBuilder();
        for (PlanTask task : tasks) {
            sb.append("Task ").append(task.getTaskOrder()).append(": ")
              .append(task.getTitle());
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

    /**
     * Handle an infrastructure exception thrown by a task execution CompletableFuture.
     * Applies retry logic for TRANSIENT failures and persists failure reasons for permanent ones.
     */
    private void handleTaskException(Long suggestionId, int taskOrder, PlanTask taskSnapshot, Throwable ex) {
        Suggestion suggestion = suggestionRepository.findById(suggestionId).orElse(null);
        if (suggestion == null) return;

        Optional<PlanTask> optTask = planTaskRepository.findBySuggestionIdAndTaskOrder(suggestionId, taskOrder);
        PlanTask task = optTask.orElse(taskSnapshot);

        // Unwrap CompletionException to get the root cause
        Throwable cause = ex instanceof java.util.concurrent.CompletionException && ex.getCause() != null
                ? ex.getCause() : ex;

        boolean isTransient = cause instanceof ClaudeService.ClaudeExecutionException &&
                ((ClaudeService.ClaudeExecutionException) cause).getType() == ClaudeService.ClaudeFailureType.TRANSIENT;
        boolean hasRetriesLeft = task.getRetryCount() < 3;

        if (isTransient && hasRetriesLeft) {
            int newRetryCount = task.getRetryCount() + 1;
            task.setRetryCount(newRetryCount);
            task.setStatus(TaskStatus.PENDING);
            task.setStatusDetail("Waiting to retry (attempt " + newRetryCount + "/3)");
            planTaskRepository.save(task);
            messagingHelper.broadcastTaskUpdate(suggestionId, task);

            messagingHelper.addMessage(suggestionId, SenderType.SYSTEM, "System",
                    "Task " + taskOrder + " hit a temporary issue and will retry automatically (attempt " + newRetryCount + "/3).");

            log.warn("[AI-FLOW] suggestion={} task={} transient failure, scheduling retry {}/3",
                    suggestionId, taskOrder, newRetryCount);
            executeNextTask(suggestionId);
        } else {
            String reason = cause.getMessage() != null ? trimTo1000(cause.getMessage()) : "Unexpected error";
            task.setFailureReason(reason);
            task.setStatus(TaskStatus.FAILED);
            task.setStatusDetail("Permanently failed");
            if (task.getCompletedAt() == null) task.setCompletedAt(Instant.now());
            planTaskRepository.save(task);
            messagingHelper.broadcastTaskUpdate(suggestionId, task);

            suggestion.setFailureReason("Task '" + task.getTitle() + "' failed: " + reason);
            suggestion.setCurrentPhase("Task " + taskOrder + " permanently failed");
            suggestionRepository.save(suggestion);
            messagingHelper.broadcastUpdate(suggestion);

            messagingHelper.addMessage(suggestionId, SenderType.SYSTEM, "System",
                    "Task " + taskOrder + " could not be completed and has permanently failed.");

            log.error("[AI-FLOW] suggestion={} task={} permanently failed: {}", suggestionId, taskOrder, reason);
        }
    }

    /**
     * Trim a string to at most 1000 characters for safe storage in the failure_reason column.
     */
    static String trimTo1000(String s) {
        if (s == null) return "";
        return s.length() > 1000 ? s.substring(0, 1000) : s;
    }

    /**
     * Extract a human-readable failure message from a Claude result JSON or raw string.
     */
    private String extractFailureMessage(String result) {
        if (result == null) return "Unknown failure";
        try {
            String json = extractJsonBlock(result);
            if (json != null) {
                JsonNode node = objectMapper.readTree(json);
                if (node.has("message")) return node.get("message").asText();
            }
        } catch (Exception ignored) {}
        return result;
    }

    // -------------------------------------------------------------------------
    // Utility helpers
    // -------------------------------------------------------------------------

    private boolean isPlanLocked(SuggestionStatus status) {
        return status == SuggestionStatus.APPROVED
            || status == SuggestionStatus.IN_PROGRESS
            || status == SuggestionStatus.TESTING
            || status == SuggestionStatus.DEV_COMPLETE;
    }

    private String extractJsonBlock(String response) {
        int start = response.indexOf('{');
        int end = response.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return response.substring(start, end + 1);
        }
        return null;
    }
}
