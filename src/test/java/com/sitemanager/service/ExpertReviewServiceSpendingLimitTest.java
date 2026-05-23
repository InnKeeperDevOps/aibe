package com.sitemanager.service;

import com.sitemanager.model.Suggestion;
import com.sitemanager.model.SuggestionMessage;
import com.sitemanager.model.enums.ExpertRole;
import com.sitemanager.model.enums.SenderType;
import com.sitemanager.model.enums.SuggestionStatus;
import com.sitemanager.repository.ExpertReviewCostRepository;
import com.sitemanager.repository.PlanTaskRepository;
import com.sitemanager.repository.SuggestionMessageRepository;
import com.sitemanager.repository.SuggestionRepository;
import com.sitemanager.repository.UserRepository;
import com.sitemanager.websocket.SuggestionWebSocketHandler;
import com.sitemanager.websocket.UserNotificationWebSocketHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies {@link ExpertReviewService} refuses to launch a new review when
 * {@link SpendingLimitService} reports the budget has been reached, while
 * still letting any review whose CLI call has already started finish.
 */
class ExpertReviewServiceSpendingLimitTest {

    private SuggestionRepository suggestionRepository;
    private ClaudeService claudeService;
    private SuggestionMessagingHelper messagingHelper;
    private SpendingLimitService spendingLimitService;
    private ExpertReviewService service;

    @BeforeEach
    void setUp() {
        suggestionRepository = mock(SuggestionRepository.class);
        SuggestionMessageRepository messageRepository = mock(SuggestionMessageRepository.class);
        PlanTaskRepository planTaskRepository = mock(PlanTaskRepository.class);
        claudeService = mock(ClaudeService.class);
        SuggestionWebSocketHandler webSocketHandler = mock(SuggestionWebSocketHandler.class);
        UserNotificationWebSocketHandler userNotificationHandler = mock(UserNotificationWebSocketHandler.class);
        SlackNotificationService slackNotificationService = mock(SlackNotificationService.class);
        UserRepository userRepository = mock(UserRepository.class);
        ExpertReviewCostRepository costRepository = mock(ExpertReviewCostRepository.class);
        messagingHelper = mock(SuggestionMessagingHelper.class);
        spendingLimitService = mock(SpendingLimitService.class);

        when(slackNotificationService.sendNotification(any(), any()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(slackNotificationService.sendApprovalNeededNotification(any()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(userRepository.findByRole(any())).thenReturn(List.of());
        when(suggestionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        SuggestionMessage stubMessage = new SuggestionMessage(1L, SenderType.AI, "Expert", "msg");
        stubMessage.setId(99L);
        when(messageRepository.save(any())).thenReturn(stubMessage);
        when(messagingHelper.addMessage(any(), any(), any(), any())).thenReturn(stubMessage);
        when(messagingHelper.escapeJson(any())).thenAnswer(inv -> {
            String s = inv.getArgument(0);
            return s == null ? "" : s;
        });
        when(planTaskRepository.findBySuggestionIdOrderByTaskOrder(any())).thenReturn(List.of());
        when(claudeService.generateSessionId()).thenReturn("test-session");
        when(claudeService.getMainRepoDir()).thenReturn("/tmp/repo");
        when(claudeService.expertReview(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), anyInt(), any()))
                .thenReturn(new CompletableFuture<>());
        when(claudeService.reviewExpertFeedback(any(), any(), any(), any(), any(), any(), any(), any(), any(), anyInt(), any()))
                .thenReturn(new CompletableFuture<>());
        when(claudeService.continueConversation(any(), any(), any(), any(), any()))
                .thenReturn(new CompletableFuture<>());

        service = new ExpertReviewService(
                suggestionRepository,
                messageRepository,
                planTaskRepository,
                claudeService,
                messagingHelper,
                webSocketHandler,
                userNotificationHandler,
                slackNotificationService,
                userRepository,
                costRepository,
                mock(CostRollupService.class),
                spendingLimitService,
                mock(SpendingAlertService.class)
        );
    }

    // -------------------------------------------------------------------------
    // runSingleExpertReview path — gated by spending limit
    // -------------------------------------------------------------------------

    @Test
    void newReviewBlockedWhenPerSuggestionLimitReached_doesNotInvokeClaude() {
        // Step that maps to a single (non-batched) expert review — pick the
        // last step so the dispatcher chooses runSingleExpertReview.
        int singleStep = ExpertRole.reviewOrder().length - 1;
        Suggestion s = buildExpertReviewSuggestion(100L, singleStep);

        when(spendingLimitService.checkCanStartReview(100L)).thenReturn(
                SpendingLimitService.LimitCheck.refused(
                        "The per-suggestion spending limit has been reached."));

        service.startExpertReviewPipeline(100L);

        verify(claudeService, never()).expertReview(any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), anyInt(), any());
    }

    @Test
    void newReviewBlockedSendsClearSystemMessage() {
        int singleStep = ExpertRole.reviewOrder().length - 1;
        Suggestion s = buildExpertReviewSuggestion(101L, singleStep);

        String refusalReason = "Per-suggestion spending limit reached.";
        when(spendingLimitService.checkCanStartReview(101L)).thenReturn(
                SpendingLimitService.LimitCheck.refused(refusalReason));

        service.startExpertReviewPipeline(101L);

        ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
        verify(messagingHelper, atLeastOnce()).addMessage(eq(101L), eq(SenderType.SYSTEM),
                any(), messageCaptor.capture());
        assertThat(messageCaptor.getAllValues()).anyMatch(m -> m.equals(refusalReason));
    }

    @Test
    void newReviewBlockedSetsPausedPhase() {
        int singleStep = ExpertRole.reviewOrder().length - 1;
        Suggestion s = buildExpertReviewSuggestion(102L, singleStep);

        when(spendingLimitService.checkCanStartReview(102L)).thenReturn(
                SpendingLimitService.LimitCheck.refused(
                        "The overall spending limit has been reached."));

        service.startExpertReviewPipeline(102L);

        assertThat(s.getCurrentPhase()).contains("Paused");
        assertThat(s.getCurrentPhase()).contains("spending limit");
    }

    @Test
    void newReviewBlockedDoesNotAdvanceStep() {
        int singleStep = ExpertRole.reviewOrder().length - 1;
        Suggestion s = buildExpertReviewSuggestion(103L, singleStep);
        Integer originalStep = s.getExpertReviewStep();

        when(spendingLimitService.checkCanStartReview(103L)).thenReturn(
                SpendingLimitService.LimitCheck.refused("paused"));

        service.startExpertReviewPipeline(103L);

        assertThat(s.getExpertReviewStep()).isEqualTo(originalStep);
        assertThat(s.getStatus()).isEqualTo(SuggestionStatus.EXPERT_REVIEW);
    }

    @Test
    void newReviewAllowedWhenBudgetCheckPasses_invokesClaude() {
        int singleStep = ExpertRole.reviewOrder().length - 1;
        Suggestion s = buildExpertReviewSuggestion(104L, singleStep);

        when(spendingLimitService.checkCanStartReview(104L))
                .thenReturn(SpendingLimitService.LimitCheck.allowed());

        service.startExpertReviewPipeline(104L);

        verify(claudeService).expertReview(any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), anyInt(), any());
    }

    // -------------------------------------------------------------------------
    // runExpertBatch path — gated once for the entire batch
    // -------------------------------------------------------------------------

    @Test
    void batchedReviewBlockedWhenLimitReached_doesNotInvokeClaude() {
        // Step 0 is the start of the first batched stage in the review order.
        Suggestion s = buildExpertReviewSuggestion(110L, 0);

        when(spendingLimitService.checkCanStartReview(110L)).thenReturn(
                SpendingLimitService.LimitCheck.refused("limit reached"));

        service.startExpertReviewPipeline(110L);

        verify(claudeService, never()).expertReview(any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), anyInt(), any());
    }

    // -------------------------------------------------------------------------
    // Reviewer call path — gated when an expert proposes changes
    // -------------------------------------------------------------------------

    @Test
    void reviewerFeedbackCallBlockedWhenLimitReached() {
        Suggestion s = buildExpertReviewSuggestion(120L, 0);

        when(spendingLimitService.checkCanStartReview(120L))
                .thenReturn(SpendingLimitService.LimitCheck.refused(
                        "spending limit reached"));

        String response = "```json{\"status\":\"CHANGES_PROPOSED\","
                + "\"analysis\":\"This is a detailed analysis with more than enough content to exceed the minimum length threshold for substantive analysis.\","
                + "\"proposedChanges\":\"Do X\","
                + "\"message\":\"Change required\"}```";

        service.handleExpertReviewResponse(120L, response, ExpertRole.QA_ENGINEER, "sess");

        verify(claudeService, never()).reviewExpertFeedback(any(), any(), any(), any(), any(),
                any(), any(), any(), any(), anyInt(), any());
    }

    // -------------------------------------------------------------------------
    // Detailed re-invoke path — gated when an expert is asked to redo a review
    // -------------------------------------------------------------------------

    @Test
    void reInvokeExpertBlockedWhenLimitReached() throws Exception {
        Suggestion s = buildExpertReviewSuggestion(130L, 0);

        when(spendingLimitService.checkCanStartReview(130L)).thenReturn(
                SpendingLimitService.LimitCheck.refused("over budget"));

        Method m = ExpertReviewService.class.getDeclaredMethod(
                "reInvokeExpertForDetailedReview", Long.class, ExpertRole.class, String.class);
        m.setAccessible(true);
        m.invoke(service, 130L, ExpertRole.QA_ENGINEER, "session");

        verify(claudeService, never()).expertReview(any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), anyInt(), any());
    }

    // -------------------------------------------------------------------------
    // Allow-path also checks (verifies the budget gate IS exercised)
    // -------------------------------------------------------------------------

    @Test
    void spendingLimitServiceConsultedOnEachStartAttempt() {
        int singleStep = ExpertRole.reviewOrder().length - 1;
        buildExpertReviewSuggestion(140L, singleStep);

        when(spendingLimitService.checkCanStartReview(140L))
                .thenReturn(SpendingLimitService.LimitCheck.allowed());

        service.startExpertReviewPipeline(140L);

        verify(spendingLimitService).checkCanStartReview(140L);
    }

    @Test
    void exceptionFromSpendingCheckIsToleratedAndReviewProceeds() {
        int singleStep = ExpertRole.reviewOrder().length - 1;
        buildExpertReviewSuggestion(150L, singleStep);

        when(spendingLimitService.checkCanStartReview(150L))
                .thenThrow(new RuntimeException("settings unreachable"));

        // Service must not propagate the error — budget tracking must never
        // disrupt the review pipeline outright.
        service.startExpertReviewPipeline(150L);

        verify(claudeService).expertReview(any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), anyInt(), any());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private Suggestion buildExpertReviewSuggestion(long id, int step) {
        Suggestion s = new Suggestion();
        s.setId(id);
        s.setTitle("Title");
        s.setDescription("Description");
        s.setStatus(SuggestionStatus.EXPERT_REVIEW);
        s.setExpertReviewStep(step);
        s.setExpertReviewRound(1);
        when(suggestionRepository.findById(id)).thenReturn(Optional.of(s));
        return s;
    }
}
