package com.sitemanager.service;

import com.sitemanager.model.ExpertReviewCost;
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

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests that {@link ExpertReviewService#recordReviewCost} persists a cost
 * record using the data {@link ClaudeService} captured for the review's
 * session, and that it gracefully handles missing data or persistence errors.
 */
class ExpertReviewServiceCostRecordingTest {

    private ClaudeService claudeService;
    private ExpertReviewCostRepository costRepository;
    private CostRollupService costRollupService;
    private SpendingAlertService spendingAlertService;
    private ExpertReviewService service;

    @BeforeEach
    void setUp() {
        SuggestionRepository suggestionRepository = mock(SuggestionRepository.class);
        SuggestionMessageRepository messageRepository = mock(SuggestionMessageRepository.class);
        PlanTaskRepository planTaskRepository = mock(PlanTaskRepository.class);
        claudeService = mock(ClaudeService.class);
        SuggestionMessagingHelper messagingHelper = mock(SuggestionMessagingHelper.class);
        SuggestionWebSocketHandler webSocketHandler = mock(SuggestionWebSocketHandler.class);
        UserNotificationWebSocketHandler userNotificationHandler = mock(UserNotificationWebSocketHandler.class);
        SlackNotificationService slackNotificationService = mock(SlackNotificationService.class);
        UserRepository userRepository = mock(UserRepository.class);
        costRepository = mock(ExpertReviewCostRepository.class);
        costRollupService = mock(CostRollupService.class);
        spendingAlertService = mock(SpendingAlertService.class);

        when(costRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        SpendingLimitService spendingLimitService = mock(SpendingLimitService.class);
        when(spendingLimitService.checkCanStartReview(any()))
                .thenReturn(SpendingLimitService.LimitCheck.allowed());

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
                costRollupService,
                spendingLimitService,
                spendingAlertService
        );
    }

    @Test
    void recordReviewCost_savesRecordWithAllFieldsWhenCostAvailable() {
        ClaudeCostInfo cost = new ClaudeCostInfo(
                "claude-opus-4-7", 1234, 567, 8900, 12,
                new BigDecimal("0.876543"), 4500);
        when(claudeService.pollSessionCost("sess-A")).thenReturn(cost);

        service.recordReviewCost(99L, "Security Engineer", "sess-A",
                "expert-review:Security Engineer");

        ArgumentCaptor<ExpertReviewCost> captor = ArgumentCaptor.forClass(ExpertReviewCost.class);
        verify(costRepository).save(captor.capture());
        ExpertReviewCost saved = captor.getValue();

        assertThat(saved.getSuggestionId()).isEqualTo(99L);
        assertThat(saved.getExpertName()).isEqualTo("Security Engineer");
        assertThat(saved.getReviewSessionId()).isEqualTo("sess-A");
        assertThat(saved.getOperationType()).isEqualTo("expert-review:Security Engineer");
        assertThat(saved.getModel()).isEqualTo("claude-opus-4-7");
        assertThat(saved.getInputTokens()).isEqualTo(1234);
        assertThat(saved.getOutputTokens()).isEqualTo(567);
        assertThat(saved.getCacheReadInputTokens()).isEqualTo(8900);
        assertThat(saved.getCacheCreationInputTokens()).isEqualTo(12);
        assertThat(saved.getCostUsd()).isEqualByComparingTo(new BigDecimal("0.876543"));
        assertThat(saved.getDurationMs()).isEqualTo(4500);
    }

    @Test
    void recordReviewCost_silentlySkipsWhenNoCostAvailable() {
        when(claudeService.pollSessionCost("missing-session")).thenReturn(null);

        service.recordReviewCost(1L, "QA Engineer", "missing-session", "expert-review:QA Engineer");

        verify(costRepository, never()).save(any());
    }

    @Test
    void recordReviewCost_skipsWhenSuggestionIdMissing() {
        service.recordReviewCost(null, "QA Engineer", "s", "op");
        verify(costRepository, never()).save(any());
    }

    @Test
    void recordReviewCost_skipsWhenExpertNameMissing() {
        service.recordReviewCost(1L, null, "s", "op");
        verify(costRepository, never()).save(any());
    }

    @Test
    void recordReviewCost_skipsWhenSessionIdMissing() {
        service.recordReviewCost(1L, "QA Engineer", null, "op");
        verify(costRepository, never()).save(any());
    }

    @Test
    void recordReviewCost_broadcastsUpdatedTotalsAfterSave() {
        ClaudeCostInfo cost = new ClaudeCostInfo(
                "model", 10, 5, 0, 0, new BigDecimal("0.25"), 100);
        when(claudeService.pollSessionCost("sess-broadcast")).thenReturn(cost);

        service.recordReviewCost(7L, "QA Engineer", "sess-broadcast",
                "expert-review:QA Engineer");

        verify(costRepository).save(any());
        verify(costRollupService).broadcastUpdatedTotals(eq(7L));
    }

    @Test
    void recordReviewCost_doesNotBroadcastWhenCostUnavailable() {
        when(claudeService.pollSessionCost("nope")).thenReturn(null);

        service.recordReviewCost(7L, "QA Engineer", "nope", "expert-review:QA Engineer");

        verify(costRepository, never()).save(any());
        verify(costRollupService, never()).broadcastUpdatedTotals(any());
    }

    @Test
    void recordReviewCost_swallowsPersistenceErrors() {
        ClaudeCostInfo cost = new ClaudeCostInfo(
                "model", 1, 1, 0, 0, BigDecimal.ZERO, 0);
        when(claudeService.pollSessionCost("s")).thenReturn(cost);
        when(costRepository.save(any())).thenThrow(new RuntimeException("db down"));

        // Must not propagate — cost tracking must never break the review pipeline.
        service.recordReviewCost(1L, "QA Engineer", "s", "op");
    }

    @Test
    void recordReviewCost_invokesSpendingAlertEvaluationAfterSave() {
        ClaudeCostInfo cost = new ClaudeCostInfo(
                "model", 1, 1, 0, 0, new BigDecimal("0.01"), 50);
        when(claudeService.pollSessionCost("sess-alert")).thenReturn(cost);

        service.recordReviewCost(42L, "QA Engineer", "sess-alert", "expert-review:QA Engineer");

        // Alert evaluation must run for the suggestion that just recorded a cost,
        // so admins are warned the moment thresholds are crossed.
        verify(spendingAlertService).evaluateAfterCostRecorded(eq(42L));
    }

    @Test
    void recordReviewCost_alertFailureDoesNotPropagate() {
        ClaudeCostInfo cost = new ClaudeCostInfo(
                "model", 1, 1, 0, 0, new BigDecimal("0.01"), 50);
        when(claudeService.pollSessionCost("sess-fail")).thenReturn(cost);
        org.mockito.Mockito.doThrow(new RuntimeException("alert pipeline broken"))
                .when(spendingAlertService).evaluateAfterCostRecorded(any());

        // Even when alerting throws, the review pipeline must not break.
        service.recordReviewCost(1L, "QA Engineer", "sess-fail", "op");
    }
}
