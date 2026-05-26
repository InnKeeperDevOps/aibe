package com.sitemanager.service;

import com.sitemanager.model.PlanTask;
import com.sitemanager.model.SiteSettings;
import com.sitemanager.model.Suggestion;
import com.sitemanager.model.SuggestionMessage;
import com.sitemanager.model.enums.SenderType;
import com.sitemanager.model.enums.SuggestionStatus;
import com.sitemanager.model.enums.TaskStatus;
import com.sitemanager.repository.PlanTaskRepository;
import com.sitemanager.repository.SuggestionRepository;
import com.sitemanager.websocket.SuggestionWebSocketHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for PlanExecutionService task failure handling:
 * retry tracking, failure reason persistence, and trimTo1000 utility.
 */
class PlanExecutionServiceFailureHandlingTest {

    private SuggestionRepository suggestionRepository;
    private PlanTaskRepository planTaskRepository;
    private ClaudeService claudeService;
    private SuggestionMessagingHelper messagingHelper;
    private SiteSettingsService settingsService;
    private SuggestionWebSocketHandler webSocketHandler;
    private SlackNotificationService slackNotificationService;
    private PlanExecutionService service;

    @BeforeEach
    void setUp() {
        suggestionRepository = mock(SuggestionRepository.class);
        planTaskRepository = mock(PlanTaskRepository.class);
        claudeService = mock(ClaudeService.class);
        messagingHelper = mock(SuggestionMessagingHelper.class);
        settingsService = mock(SiteSettingsService.class);
        webSocketHandler = mock(SuggestionWebSocketHandler.class);
        slackNotificationService = mock(SlackNotificationService.class);

        when(slackNotificationService.sendNotification(any(), any()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(suggestionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(planTaskRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        SuggestionMessage stubMsg = new SuggestionMessage(1L, SenderType.SYSTEM, "System", "msg");
        stubMsg.setId(1L);
        when(messagingHelper.addMessage(any(), any(), any(), any())).thenReturn(stubMsg);
        when(messagingHelper.escapeJson(any())).thenAnswer(inv -> {
            String s = inv.getArgument(0);
            return s == null ? "" : s;
        });

        SiteSettings defaultSettings = new SiteSettings();
        defaultSettings.setTargetRepoUrl(null);
        defaultSettings.setGithubToken(null);
        defaultSettings.setMaxConcurrentSuggestions(1);
        when(settingsService.getSettings()).thenReturn(defaultSettings);

        when(suggestionRepository.findByStatus(any())).thenReturn(new java.util.ArrayList<>());
        when(suggestionRepository.countByStatusIn(any())).thenReturn(0L);
        when(planTaskRepository.findBySuggestionIdOrderByTaskOrder(any())).thenReturn(new java.util.ArrayList<>());
        when(claudeService.generateSessionId()).thenReturn("test-session");
        when(claudeService.executeSingleTask(any(), any(), any(), anyInt(), any(), any(), anyInt(), any(), any(), any()))
                .thenReturn(CompletableFuture.completedFuture("{}"));

        service = new PlanExecutionService(
                suggestionRepository,
                planTaskRepository,
                claudeService,
                messagingHelper,
                settingsService,
                webSocketHandler,
                slackNotificationService
        );
    }

    // -------------------------------------------------------------------------
    // trimTo1000
    // -------------------------------------------------------------------------

    @Test
    void trimTo1000_shortString_returnsUnchanged() {
        assertThat(PlanExecutionService.trimTo1000("hello")).isEqualTo("hello");
    }

    @Test
    void trimTo1000_nullInput_returnsEmptyString() {
        assertThat(PlanExecutionService.trimTo1000(null)).isEqualTo("");
    }

    @Test
    void trimTo1000_exactlyAtLimit_returnsUnchanged() {
        String s = "x".repeat(1000);
        assertThat(PlanExecutionService.trimTo1000(s)).hasSize(1000);
    }

    @Test
    void trimTo1000_overLimit_isTruncatedTo1000() {
        String s = "a".repeat(1500);
        String result = PlanExecutionService.trimTo1000(s);
        assertThat(result).hasSize(1000);
        assertThat(result).isEqualTo("a".repeat(1000));
    }

    // -------------------------------------------------------------------------
    // handleTaskException — TRANSIENT failure with retries remaining
    // -------------------------------------------------------------------------

    @Test
    void handleTaskException_transientFailure_incrementsRetryCount() throws Exception {
        Suggestion suggestion = suggestion(1L);
        PlanTask task = planTask(1, TaskStatus.IN_PROGRESS, 0);
        when(suggestionRepository.findById(1L)).thenReturn(Optional.of(suggestion));
        when(planTaskRepository.findBySuggestionIdAndTaskOrder(1L, 1)).thenReturn(Optional.of(task));
        when(planTaskRepository.findBySuggestionIdOrderByTaskOrder(1L)).thenReturn(List.of(task));

        Throwable transientEx = new ClaudeService.ClaudeExecutionException(
                "rate limit hit", ClaudeService.ClaudeFailureType.TRANSIENT, 1);
        invokeHandleTaskException(1L, 1, task, transientEx);

        assertThat(task.getRetryCount()).isEqualTo(1);
    }

    @Test
    void handleTaskException_transientFailure_triggersRetryExecution() throws Exception {
        Suggestion suggestion = suggestion(2L);
        PlanTask task = planTask(1, TaskStatus.IN_PROGRESS, 0);
        when(suggestionRepository.findById(2L)).thenReturn(Optional.of(suggestion));
        when(planTaskRepository.findBySuggestionIdAndTaskOrder(2L, 1)).thenReturn(Optional.of(task));
        when(planTaskRepository.findBySuggestionIdOrderByTaskOrder(2L)).thenReturn(List.of(task));

        Throwable transientEx = new ClaudeService.ClaudeExecutionException(
                "timed out", ClaudeService.ClaudeFailureType.TRANSIENT, 1);
        invokeHandleTaskException(2L, 1, task, transientEx);

        // Verify retry was kicked off — executeSingleTask called for the retry
        verify(claudeService).executeSingleTask(any(), any(), any(), anyInt(), any(), any(), anyInt(), any(), any(), any());
    }

    @Test
    void handleTaskException_transientFailure_broadcastsRetryMessage() throws Exception {
        Suggestion suggestion = suggestion(3L);
        PlanTask task = planTask(1, TaskStatus.IN_PROGRESS, 1);
        when(suggestionRepository.findById(3L)).thenReturn(Optional.of(suggestion));
        when(planTaskRepository.findBySuggestionIdAndTaskOrder(3L, 1)).thenReturn(Optional.of(task));
        when(planTaskRepository.findBySuggestionIdOrderByTaskOrder(3L)).thenReturn(List.of(task));

        Throwable transientEx = new ClaudeService.ClaudeExecutionException(
                "overloaded", ClaudeService.ClaudeFailureType.TRANSIENT, 1);
        invokeHandleTaskException(3L, 1, task, transientEx);

        verify(messagingHelper).addMessage(eq(3L), eq(SenderType.SYSTEM), eq("System"),
                argThat(msg -> msg.contains("temporary issue") && msg.contains("attempt 2/3")));
    }

    @Test
    void handleTaskException_transientFailureWrappedInCompletionException_appliesRetryLogic() throws Exception {
        Suggestion suggestion = suggestion(4L);
        PlanTask task = planTask(1, TaskStatus.IN_PROGRESS, 0);
        when(suggestionRepository.findById(4L)).thenReturn(Optional.of(suggestion));
        when(planTaskRepository.findBySuggestionIdAndTaskOrder(4L, 1)).thenReturn(Optional.of(task));
        when(planTaskRepository.findBySuggestionIdOrderByTaskOrder(4L)).thenReturn(List.of(task));

        ClaudeService.ClaudeExecutionException cause = new ClaudeService.ClaudeExecutionException(
                "timed out", ClaudeService.ClaudeFailureType.TRANSIENT, 1);
        Throwable wrapped = new CompletionException(cause);
        invokeHandleTaskException(4L, 1, task, wrapped);

        // Retry count incremented and retry execution triggered
        assertThat(task.getRetryCount()).isEqualTo(1);
        verify(claudeService).executeSingleTask(any(), any(), any(), anyInt(), any(), any(), anyInt(), any(), any(), any());
    }

    // -------------------------------------------------------------------------
    // handleTaskException — TRANSIENT failure with retries exhausted
    // -------------------------------------------------------------------------

    @Test
    void handleTaskException_transientFailureRetriesExhausted_setsFailedStatus() throws Exception {
        Suggestion suggestion = suggestion(5L);
        PlanTask task = planTask(1, TaskStatus.IN_PROGRESS, 3); // already at max
        task.setTitle("Build login form");
        when(suggestionRepository.findById(5L)).thenReturn(Optional.of(suggestion));
        when(planTaskRepository.findBySuggestionIdAndTaskOrder(5L, 1)).thenReturn(Optional.of(task));

        Throwable transientEx = new ClaudeService.ClaudeExecutionException(
                "overloaded", ClaudeService.ClaudeFailureType.TRANSIENT, 4);
        invokeHandleTaskException(5L, 1, task, transientEx);

        assertThat(task.getStatus()).isEqualTo(TaskStatus.FAILED);
    }

    @Test
    void handleTaskException_transientFailureRetriesExhausted_setsTaskFailureReason() throws Exception {
        Suggestion suggestion = suggestion(6L);
        PlanTask task = planTask(1, TaskStatus.IN_PROGRESS, 3);
        task.setTitle("Build login form");
        when(suggestionRepository.findById(6L)).thenReturn(Optional.of(suggestion));
        when(planTaskRepository.findBySuggestionIdAndTaskOrder(6L, 1)).thenReturn(Optional.of(task));

        Throwable transientEx = new ClaudeService.ClaudeExecutionException(
                "API overloaded", ClaudeService.ClaudeFailureType.TRANSIENT, 4);
        invokeHandleTaskException(6L, 1, task, transientEx);

        assertThat(task.getFailureReason()).contains("API overloaded");
    }

    @Test
    void handleTaskException_transientFailureRetriesExhausted_setsSuggestionFailureReason() throws Exception {
        Suggestion suggestion = suggestion(7L);
        PlanTask task = planTask(1, TaskStatus.IN_PROGRESS, 3);
        task.setTitle("Build login form");
        when(suggestionRepository.findById(7L)).thenReturn(Optional.of(suggestion));
        when(planTaskRepository.findBySuggestionIdAndTaskOrder(7L, 1)).thenReturn(Optional.of(task));

        Throwable transientEx = new ClaudeService.ClaudeExecutionException(
                "API overloaded", ClaudeService.ClaudeFailureType.TRANSIENT, 4);
        invokeHandleTaskException(7L, 1, task, transientEx);

        assertThat(suggestion.getFailureReason()).contains("Build login form");
        assertThat(suggestion.getFailureReason()).contains("API overloaded");
    }

    @Test
    void handleTaskException_transientFailureRetriesExhausted_setsCurrentPhase() throws Exception {
        Suggestion suggestion = suggestion(8L);
        PlanTask task = planTask(2, TaskStatus.IN_PROGRESS, 3);
        task.setTitle("Run tests");
        when(suggestionRepository.findById(8L)).thenReturn(Optional.of(suggestion));
        when(planTaskRepository.findBySuggestionIdAndTaskOrder(8L, 2)).thenReturn(Optional.of(task));

        invokeHandleTaskException(8L, 2, task,
                new ClaudeService.ClaudeExecutionException("error", ClaudeService.ClaudeFailureType.TRANSIENT, 4));

        assertThat(suggestion.getCurrentPhase()).contains("Task 2");
        assertThat(suggestion.getCurrentPhase()).contains("permanently failed");
    }

    // -------------------------------------------------------------------------
    // handleTaskException — PERMANENT failure
    // -------------------------------------------------------------------------

    @Test
    void handleTaskException_permanentFailure_setsTaskStatusFailed() throws Exception {
        Suggestion suggestion = suggestion(9L);
        PlanTask task = planTask(1, TaskStatus.IN_PROGRESS, 0);
        task.setTitle("Setup DB");
        when(suggestionRepository.findById(9L)).thenReturn(Optional.of(suggestion));
        when(planTaskRepository.findBySuggestionIdAndTaskOrder(9L, 1)).thenReturn(Optional.of(task));

        Throwable permanentEx = new ClaudeService.ClaudeExecutionException(
                "Invalid API key", ClaudeService.ClaudeFailureType.PERMANENT, 1);
        invokeHandleTaskException(9L, 1, task, permanentEx);

        assertThat(task.getStatus()).isEqualTo(TaskStatus.FAILED);
    }

    @Test
    void handleTaskException_permanentFailure_doesNotRetry() throws Exception {
        Suggestion suggestion = suggestion(10L);
        PlanTask task = planTask(1, TaskStatus.IN_PROGRESS, 0);
        task.setTitle("Setup DB");
        when(suggestionRepository.findById(10L)).thenReturn(Optional.of(suggestion));
        when(planTaskRepository.findBySuggestionIdAndTaskOrder(10L, 1)).thenReturn(Optional.of(task));
        when(planTaskRepository.findBySuggestionIdOrderByTaskOrder(10L)).thenReturn(List.of(task));
        when(claudeService.generateSessionId()).thenReturn("session");

        Throwable permanentEx = new ClaudeService.ClaudeExecutionException(
                "model not found", ClaudeService.ClaudeFailureType.PERMANENT, 1);
        invokeHandleTaskException(10L, 1, task, permanentEx);

        // retryCount should not change (no retry happened)
        assertThat(task.getRetryCount()).isEqualTo(0);
    }

    @Test
    void handleTaskException_permanentFailure_setsSuggestionAndTaskFailureReason() throws Exception {
        Suggestion suggestion = suggestion(11L);
        PlanTask task = planTask(1, TaskStatus.IN_PROGRESS, 0);
        task.setTitle("Deploy changes");
        when(suggestionRepository.findById(11L)).thenReturn(Optional.of(suggestion));
        when(planTaskRepository.findBySuggestionIdAndTaskOrder(11L, 1)).thenReturn(Optional.of(task));

        Throwable permanentEx = new ClaudeService.ClaudeExecutionException(
                "CLI argument error", ClaudeService.ClaudeFailureType.PERMANENT, 1);
        invokeHandleTaskException(11L, 1, task, permanentEx);

        assertThat(task.getFailureReason()).contains("CLI argument error");
        assertThat(suggestion.getFailureReason()).contains("Deploy changes");
        assertThat(suggestion.getFailureReason()).contains("CLI argument error");
    }

    @Test
    void handleTaskException_permanentFailure_failureReasonTrimmedTo1000Chars() throws Exception {
        Suggestion suggestion = suggestion(12L);
        PlanTask task = planTask(1, TaskStatus.IN_PROGRESS, 0);
        task.setTitle("Big task");
        when(suggestionRepository.findById(12L)).thenReturn(Optional.of(suggestion));
        when(planTaskRepository.findBySuggestionIdAndTaskOrder(12L, 1)).thenReturn(Optional.of(task));

        String longMessage = "e".repeat(2000);
        Throwable permanentEx = new ClaudeService.ClaudeExecutionException(
                longMessage, ClaudeService.ClaudeFailureType.PERMANENT, 1);
        invokeHandleTaskException(12L, 1, task, permanentEx);

        assertThat(task.getFailureReason()).hasSize(1000);
    }

    // -------------------------------------------------------------------------
    // handleSingleTaskResult — FAILED branch stores failure reason
    // -------------------------------------------------------------------------

    @Test
    void handleSingleTaskResult_failedStatus_setsTaskFailureReason() throws Exception {
        Suggestion suggestion = suggestion(20L);
        PlanTask task = planTask(1, TaskStatus.FAILED, 0);
        task.setTitle("Write tests");
        when(suggestionRepository.findById(20L)).thenReturn(Optional.of(suggestion));
        when(planTaskRepository.findBySuggestionIdAndTaskOrder(20L, 1)).thenReturn(Optional.of(task));

        invokeHandleSingleTaskResult(20L, 1,
                "{\"taskOrder\":1,\"status\":\"FAILED\",\"message\":\"Tests could not compile\"}");

        assertThat(task.getFailureReason()).contains("Tests could not compile");
    }

    @Test
    void handleSingleTaskResult_failedStatus_setsSuggestionFailureReason() throws Exception {
        Suggestion suggestion = suggestion(21L);
        PlanTask task = planTask(1, TaskStatus.FAILED, 0);
        task.setTitle("Build API");
        when(suggestionRepository.findById(21L)).thenReturn(Optional.of(suggestion));
        when(planTaskRepository.findBySuggestionIdAndTaskOrder(21L, 1)).thenReturn(Optional.of(task));

        invokeHandleSingleTaskResult(21L, 1,
                "{\"taskOrder\":1,\"status\":\"FAILED\",\"message\":\"API endpoint broken\"}");

        assertThat(suggestion.getFailureReason()).contains("Build API");
        assertThat(suggestion.getFailureReason()).contains("API endpoint broken");
    }

    @Test
    void handleSingleTaskResult_failedStatus_longMessage_isTrimmedTo1000() throws Exception {
        Suggestion suggestion = suggestion(22L);
        PlanTask task = planTask(1, TaskStatus.FAILED, 0);
        task.setTitle("Deploy");
        when(suggestionRepository.findById(22L)).thenReturn(Optional.of(suggestion));
        when(planTaskRepository.findBySuggestionIdAndTaskOrder(22L, 1)).thenReturn(Optional.of(task));

        String longMsg = "x".repeat(2000);
        invokeHandleSingleTaskResult(22L, 1,
                "{\"taskOrder\":1,\"status\":\"FAILED\",\"message\":\"" + longMsg + "\"}");

        assertThat(task.getFailureReason()).hasSize(1000);
    }

    // -------------------------------------------------------------------------
    // executeApprovedSuggestion — stores suggestion failure reason on setup error
    // -------------------------------------------------------------------------

    @Test
    void executeApprovedSuggestion_repoNotConfigured_doesNotSetFailureReason() {
        // When repo URL is null, it bails early with a message but no failure reason
        Suggestion s = suggestion(30L);
        s.setStatus(SuggestionStatus.APPROVED);
        when(suggestionRepository.findById(30L)).thenReturn(Optional.of(s));
        when(suggestionRepository.countByStatusIn(any())).thenReturn(0L);

        service.executeApprovedSuggestion(s);

        // No failure reason set — it's just "blocked"
        assertThat(s.getFailureReason()).isNull();
    }

    // -------------------------------------------------------------------------
    // broadcastUpdate — includes failureReason
    // (testing via PlanExecutionService which calls messagingHelper)
    // -------------------------------------------------------------------------

    @Test
    void handleTaskException_permanentFailure_broadcastsUpdateWithFailureReason() throws Exception {
        Suggestion suggestion = suggestion(40L);
        PlanTask task = planTask(1, TaskStatus.IN_PROGRESS, 0);
        task.setTitle("Setup auth");
        when(suggestionRepository.findById(40L)).thenReturn(Optional.of(suggestion));
        when(planTaskRepository.findBySuggestionIdAndTaskOrder(40L, 1)).thenReturn(Optional.of(task));

        Throwable permanentEx = new ClaudeService.ClaudeExecutionException(
                "auth error", ClaudeService.ClaudeFailureType.PERMANENT, 1);
        invokeHandleTaskException(40L, 1, task, permanentEx);

        // broadcastUpdate should be called after failureReason is set
        verify(messagingHelper).broadcastUpdate(argThat(s ->
                s.getFailureReason() != null && s.getFailureReason().contains("auth error")));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private Suggestion suggestion(Long id) {
        Suggestion s = new Suggestion();
        s.setId(id);
        s.setTitle("Test suggestion " + id);
        s.setDescription("Description for " + id);
        s.setStatus(SuggestionStatus.IN_PROGRESS);
        s.setCreatedAt(Instant.now());
        return s;
    }

    private PlanTask planTask(int order, TaskStatus status, int retryCount) {
        PlanTask t = new PlanTask();
        t.setTaskOrder(order);
        t.setTitle("Task " + order);
        t.setStatus(status);
        t.setRetryCount(retryCount);
        return t;
    }

    private void invokeHandleTaskException(Long suggestionId, int taskOrder, PlanTask task, Throwable ex)
            throws Exception {
        Method m = PlanExecutionService.class.getDeclaredMethod(
                "handleTaskException", Long.class, int.class, PlanTask.class, Throwable.class);
        m.setAccessible(true);
        m.invoke(service, suggestionId, taskOrder, task, ex);
    }

    private void invokeHandleSingleTaskResult(Long suggestionId, int taskOrder, String result)
            throws Exception {
        Method m = PlanExecutionService.class.getDeclaredMethod(
                "handleSingleTaskResult", Long.class, int.class, String.class);
        m.setAccessible(true);
        m.invoke(service, suggestionId, taskOrder, result);
    }
}
