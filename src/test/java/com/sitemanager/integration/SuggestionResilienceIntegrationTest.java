package com.sitemanager.integration;

import com.sitemanager.model.PlanTask;
import com.sitemanager.model.Suggestion;
import com.sitemanager.model.SiteSettings;
import com.sitemanager.model.enums.SuggestionStatus;
import com.sitemanager.model.enums.TaskStatus;
import com.sitemanager.service.ClaudeService;
import com.sitemanager.service.SuggestionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SuggestionResilienceIntegrationTest extends SuggestionLifecycleIntegrationTest {

    @Autowired
    private SuggestionService suggestionService;

    /**
     * Seeds a suggestion in the given status with a mix of completed and pending tasks.
     * The first completedCount tasks have status=COMPLETED with completedAt set;
     * the remaining (totalCount - completedCount) tasks are PENDING.
     *
     * Sets workingDirectory to /tmp so resumeInProgressSuggestion takes the resume-from-progress
     * path rather than falling back to full re-execution from scratch.
     */
    private Suggestion seedSuggestion(SuggestionStatus status, int completedCount, int totalCount) {
        Suggestion suggestion = new Suggestion();
        suggestion.setTitle("Resilience test suggestion");
        suggestion.setDescription("Testing mid-execution restart recovery");
        suggestion.setAuthorName(regularUser.getUsername());
        suggestion.setAuthorId(regularUser.getId());
        suggestion.setStatus(status);
        suggestion.setClaudeSessionId("stale-session-id");
        suggestion.setPlanSummary("Implement X by doing Y");
        suggestion.setWorkingDirectory("/tmp");
        suggestion = suggestionRepository.save(suggestion);

        for (int i = 0; i < totalCount; i++) {
            PlanTask task = new PlanTask();
            task.setSuggestionId(suggestion.getId());
            task.setTaskOrder(i);
            task.setTitle("Task " + (i + 1));
            task.setDescription("Description for task " + (i + 1));
            if (i < completedCount) {
                task.setStatus(TaskStatus.COMPLETED);
                task.setCompletedAt(Instant.now().minusSeconds(60));
            } else {
                task.setStatus(TaskStatus.PENDING);
            }
            planTaskRepository.save(task);
        }

        return suggestion;
    }

    /**
     * Seeds an IN_PROGRESS suggestion — convenience wrapper around seedSuggestion.
     */
    private Suggestion seedInProgressSuggestion(int completedCount, int totalCount) {
        return seedSuggestion(SuggestionStatus.IN_PROGRESS, completedCount, totalCount);
    }

    private void configureSettingsForExecution() {
        SiteSettings settings = siteSettingsRepository.findAll().get(0);
        settings.setTargetRepoUrl("https://github.com/test/repo");
        settings.setGithubToken("test-github-token");
        settings.setAutoMergePr(true);
        siteSettingsRepository.save(settings);
    }

    private void stubGitAndPrMocks() throws Exception {
        when(claudeService.cloneRepository(any(), any())).thenReturn("/tmp");
        doNothing().when(claudeService).createBranch(any(), any());
        doNothing().when(claudeService).stageAllChanges(any());
        when(claudeService.getStagedDiffSummary(any())).thenReturn("1 file changed");
        when(claudeService.generateCommitMessage(any(), any(), any(), any()))
                .thenReturn("Implement suggestion changes");
        when(claudeService.commitStagedChanges(any(), any())).thenReturn(true);
        doNothing().when(claudeService).pushBranch(any(), any());
        when(claudeService.createGitHubPullRequest(any(), any(), any(), any(), any()))
                .thenReturn(Map.of("html_url", "https://github.com/test/repo/pull/1", "number", 1));
        when(claudeService.mergePullRequest(any(), anyInt(), any())).thenReturn(true);
    }

    @Test
    void midExecutionRestart_inProgressSuggestion_resumesFromFirstPendingTask() throws Exception {
        // Seed: task 0 = COMPLETED, tasks 1 & 2 = PENDING
        Suggestion suggestion = seedInProgressSuggestion(1, 3);
        long id = suggestion.getId();

        configureSettingsForExecution();
        stubGitAndPrMocks();

        // executeSingleTask returns COMPLETED for whichever task order it is called with
        when(claudeService.executeSingleTask(
                any(), any(), any(), anyInt(), any(), any(), anyInt(), any(), any(), any()))
                .thenAnswer(inv -> {
                    int taskOrder = inv.getArgument(3);
                    return CompletableFuture.completedFuture(
                            "{\"taskOrder\":" + taskOrder + ",\"status\":\"COMPLETED\","
                                    + "\"message\":\"Task " + taskOrder + " done\"}");
                });

        // reviewTaskCompletion approves all tasks
        when(claudeService.reviewTaskCompletion(
                any(), any(), any(), any(), anyInt(), any(), any(), any(), any(), any()))
                .thenAnswer(inv -> CompletableFuture.supplyAsync(() ->
                        "{\"status\":\"APPROVED\",\"analysis\":\"Looks good\","
                                + "\"message\":\"Task verified\"}"));

        // Trigger startup resume — picks up the IN_PROGRESS suggestion
        suggestionService.resumeSuggestionsOnStartup();

        // Wait for the suggestion to reach MERGED (autoMergePr=true)
        await().atMost(30, SECONDS).untilAsserted(() -> {
            Suggestion s = suggestionRepository.findById(id).orElseThrow();
            assertThat(s.getStatus()).isEqualTo(SuggestionStatus.MERGED);
        });

        // Verify executeSingleTask was never called for taskOrder=0 (already COMPLETED)
        verify(claudeService, never()).executeSingleTask(
                any(), any(), any(), eq(0), any(), any(), anyInt(), any(), any(), any());
    }

    @Test
    void claudeSessionError_taskRetried_eventuallyCompletes() throws Exception {
        // Seed a PLANNED suggestion with 3 pending tasks (no completed tasks yet)
        Suggestion suggestion = seedSuggestion(SuggestionStatus.PLANNED, 0, 3);
        long id = suggestion.getId();

        configureSettingsForExecution();
        stubGitAndPrMocks();

        // First call to executeSingleTask throws a transient session error;
        // all subsequent calls return a COMPLETED result so execution proceeds normally.
        AtomicInteger callCount = new AtomicInteger(0);
        when(claudeService.executeSingleTask(
                any(), any(), any(), anyInt(), any(), any(), anyInt(), any(), any(), any()))
                .thenAnswer(inv -> {
                    int call = callCount.incrementAndGet();
                    if (call == 1) {
                        return CompletableFuture.failedFuture(
                                new ClaudeService.ClaudeExecutionException(
                                        "Session died", ClaudeService.ClaudeFailureType.TRANSIENT, 1));
                    }
                    int taskOrder = inv.getArgument(3);
                    return CompletableFuture.completedFuture(
                            "{\"taskOrder\":" + taskOrder + ",\"status\":\"COMPLETED\","
                                    + "\"message\":\"Task " + taskOrder + " done\"}");
                });

        when(claudeService.reviewTaskCompletion(
                any(), any(), any(), any(), anyInt(), any(), any(), any(), any(), any()))
                .thenAnswer(inv -> CompletableFuture.supplyAsync(() ->
                        "{\"status\":\"APPROVED\",\"analysis\":\"Looks good\","
                                + "\"message\":\"Task verified\"}"));

        // Approve the suggestion to kick off execution
        mockMvc.perform(post("/api/suggestions/{id}/approve", id)
                        .session(adminSession()))
                .andExpect(status().isOk());

        // Wait for the suggestion to complete despite the initial session error
        await().atMost(30, SECONDS).untilAsserted(() -> {
            Suggestion s = suggestionRepository.findById(id).orElseThrow();
            assertThat(s.getStatus()).isIn(
                    SuggestionStatus.DEV_COMPLETE,
                    SuggestionStatus.FINAL_REVIEW,
                    SuggestionStatus.MERGED);
        });

        // Confirm the retry happened: executeSingleTask must have been called at least twice
        verify(claudeService, atLeast(2)).executeSingleTask(
                any(), any(), any(), anyInt(), any(), any(), anyInt(), any(), any(), any());
    }

    @Test
    void allRetriesExhausted_taskStatusBecomesFailedAndSuggestionStops() throws Exception {
        // Seed a PLANNED suggestion with 3 pending tasks
        Suggestion suggestion = seedSuggestion(SuggestionStatus.PLANNED, 0, 3);
        long id = suggestion.getId();

        // Configure repo URL so execution can start (auto-merge disabled — we expect failure before PR)
        SiteSettings settings = siteSettingsRepository.findAll().get(0);
        settings.setTargetRepoUrl("https://github.com/test/repo");
        settings.setGithubToken("test-github-token");
        settings.setAutoMergePr(false);
        siteSettingsRepository.save(settings);

        // Setup stubs needed to get past repo-clone phase and into task execution
        when(claudeService.cloneRepository(any(), any())).thenReturn("/tmp");
        doNothing().when(claudeService).createBranch(any(), any());

        // executeSingleTask always fails with a transient error to exhaust the retry budget
        when(claudeService.executeSingleTask(
                any(), any(), any(), anyInt(), any(), any(), anyInt(), any(), any(), any()))
                .thenAnswer(inv -> CompletableFuture.failedFuture(
                        new ClaudeService.ClaudeExecutionException(
                                "Session permanently unavailable",
                                ClaudeService.ClaudeFailureType.TRANSIENT, 1)));

        // Approve the suggestion to kick off execution
        mockMvc.perform(post("/api/suggestions/{id}/approve", id)
                        .session(adminSession()))
                .andExpect(status().isOk());

        // Wait until the first task is permanently marked FAILED after retries are exhausted
        await().atMost(15, SECONDS).untilAsserted(() -> {
            boolean anyFailed = planTaskRepository.findBySuggestionIdOrderByTaskOrder(id).stream()
                    .anyMatch(t -> t.getStatus() == TaskStatus.FAILED);
            assertThat(anyFailed).isTrue();
        });

        // Verify the suggestion did not advance past the failure point
        Suggestion s = suggestionRepository.findById(id).orElseThrow();
        assertThat(s.getStatus()).isNotEqualTo(SuggestionStatus.DEV_COMPLETE);
        assertThat(s.getStatus()).isNotEqualTo(SuggestionStatus.MERGED);
    }

    @Test
    void midExecutionRestart_testingStageSuggestion_completesReviewAndMerges() throws Exception {
        // Seed: TESTING status, all 3 tasks COMPLETED
        Suggestion suggestion = seedSuggestion(SuggestionStatus.TESTING, 3, 3);
        long id = suggestion.getId();

        configureSettingsForExecution();
        stubGitAndPrMocks();

        // reviewTaskCompletion approves all tasks in case any review is triggered
        when(claudeService.reviewTaskCompletion(
                any(), any(), any(), any(), anyInt(), any(), any(), any(), any(), any()))
                .thenAnswer(inv -> CompletableFuture.supplyAsync(() ->
                        "{\"status\":\"APPROVED\",\"analysis\":\"Looks good\","
                                + "\"message\":\"Task verified\"}"));

        // Trigger startup resume
        suggestionService.resumeSuggestionsOnStartup();

        // Wait for the suggestion to advance past the testing phase
        await().atMost(20, SECONDS).untilAsserted(() -> {
            Suggestion s = suggestionRepository.findById(id).orElseThrow();
            assertThat(s.getStatus()).isIn(
                    SuggestionStatus.DEV_COMPLETE,
                    SuggestionStatus.FINAL_REVIEW,
                    SuggestionStatus.MERGED);
        });
    }
}
