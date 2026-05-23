package com.sitemanager.service;

import com.sitemanager.dto.ClarificationRequest;
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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Tests for the optimized user-guidance restart in handleExpertClarificationAnswers:
 * when the user provides guidance after max review rounds, already-approved experts
 * are skipped and only those who have not approved are re-run via targeted re-review.
 */
class SuggestionServiceUserGuidanceRestartTest {

    private SuggestionRepository suggestionRepository;
    private SuggestionMessageRepository messageRepository;
    private ClaudeService claudeService;
    private SlackNotificationService slackNotificationService;
    private ExpertReviewService service;

    @BeforeEach
    void setUp() {
        suggestionRepository = mock(SuggestionRepository.class);
        messageRepository = mock(SuggestionMessageRepository.class);
        PlanTaskRepository planTaskRepository = mock(PlanTaskRepository.class);
        SuggestionWebSocketHandler webSocketHandler = mock(SuggestionWebSocketHandler.class);
        UserNotificationWebSocketHandler userNotificationHandler = mock(UserNotificationWebSocketHandler.class);
        slackNotificationService = mock(SlackNotificationService.class);
        claudeService = mock(ClaudeService.class);
        UserRepository userRepository = mock(UserRepository.class);

        when(slackNotificationService.sendNotification(any(), any()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(slackNotificationService.sendApprovalNeededNotification(any()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(userRepository.findByRole(any())).thenReturn(List.of());
        when(suggestionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        SuggestionMessage stubMessage = new SuggestionMessage(1L, SenderType.AI, "Expert", "msg");
        stubMessage.setId(1L);
        when(messageRepository.save(any())).thenReturn(stubMessage);
        when(planTaskRepository.findBySuggestionIdOrderByTaskOrder(any())).thenReturn(List.of());
        when(claudeService.expertReview(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), anyInt(), any()))
                .thenReturn(new CompletableFuture<>());

        SpendingLimitService spendingLimitService = mock(SpendingLimitService.class);
        when(spendingLimitService.checkCanStartReview(any()))
                .thenReturn(SpendingLimitService.LimitCheck.allowed());

        service = new ExpertReviewService(
                suggestionRepository,
                messageRepository,
                planTaskRepository,
                claudeService,
                mock(SuggestionMessagingHelper.class),
                webSocketHandler,
                userNotificationHandler,
                slackNotificationService,
                userRepository,
                mock(ExpertReviewCostRepository.class),
                mock(CostRollupService.class),
                spendingLimitService,
                mock(SpendingAlertService.class)
        );
    }

    /**
     * Builds a suggestion representing the "user guidance" scenario:
     * - step is null (past all experts — expert == null in handleExpertClarificationAnswers)
     * - status is EXPERT_REVIEW
     * - totalExpertReviewRounds is set (so we're past the initial round but under hard cap)
     */
    private Suggestion buildUserGuidanceSuggestion(long id,
                                                    Map<String, Suggestion.ExpertApprovalEntry> tracker) {
        Suggestion s = new Suggestion();
        s.setId(id);
        s.setStatus(SuggestionStatus.EXPERT_REVIEW);
        // step == null → ExpertRole.fromStep(0) returns PROJECT_OWNER, not null.
        // We need step >= reviewOrder().length so fromStep returns null.
        s.setExpertReviewStep(ExpertRole.reviewOrder().length);
        s.setExpertReviewRound(2);
        s.setTotalExpertReviewRounds(2);
        s.setExpertReviewPlanChanged(false);
        if (tracker != null) {
            s.setExpertApprovalMap(tracker);
        }
        when(suggestionRepository.findById(id)).thenReturn(Optional.of(s));
        return s;
    }

    private List<ClarificationRequest.ClarificationAnswer> buildAnswers(String question, String answer) {
        ClarificationRequest.ClarificationAnswer qa = new ClarificationRequest.ClarificationAnswer();
        qa.setQuestion(question);
        qa.setAnswer(answer);
        return List.of(qa);
    }

    // -------------------------------------------------------------------------
    // All experts approved → skip directly to PLANNED
    // -------------------------------------------------------------------------

    @Test
    void allExpertsApproved_transitionsToPLANNED() {
        Map<String, Suggestion.ExpertApprovalEntry> tracker = new HashMap<>();
        for (ExpertRole expert : ExpertRole.reviewOrder()) {
            tracker.put(expert.getDisplayName(), new Suggestion.ExpertApprovalEntry("APPROVED", 1));
        }

        Suggestion suggestion = buildUserGuidanceSuggestion(1L, tracker);

        service.handleExpertClarificationAnswers(1L, "user", buildAnswers("Q?", "A."));

        assertThat(suggestion.getStatus()).isEqualTo(SuggestionStatus.PLANNED);
    }

    @Test
    void allExpertsApproved_clearsReviewFields() {
        Map<String, Suggestion.ExpertApprovalEntry> tracker = new HashMap<>();
        for (ExpertRole expert : ExpertRole.reviewOrder()) {
            tracker.put(expert.getDisplayName(), new Suggestion.ExpertApprovalEntry("APPROVED", 1));
        }

        Suggestion suggestion = buildUserGuidanceSuggestion(2L, tracker);

        service.handleExpertClarificationAnswers(2L, "user", buildAnswers("Q?", "A."));

        assertThat(suggestion.getExpertReviewStep()).isNull();
        assertThat(suggestion.getExpertReviewRound()).isNull();
        assertThat(suggestion.getExpertReviewPlanChanged()).isNull();
        assertThat(suggestion.getTotalExpertReviewRounds()).isNull();
        assertThat(suggestion.getExpertReviewChangedDomains()).isNull();
        assertThat(suggestion.getCurrentPhase()).isEqualTo("Plan ready — waiting for approval");
    }

    @Test
    void allExpertsApproved_doesNotInvokeAnyExpert() {
        Map<String, Suggestion.ExpertApprovalEntry> tracker = new HashMap<>();
        for (ExpertRole expert : ExpertRole.reviewOrder()) {
            tracker.put(expert.getDisplayName(), new Suggestion.ExpertApprovalEntry("APPROVED", 1));
        }

        Suggestion suggestion = buildUserGuidanceSuggestion(3L, tracker);

        service.handleExpertClarificationAnswers(3L, "user", buildAnswers("Q?", "A."));

        verify(claudeService, never()).expertReview(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), anyInt(), any());
    }

    @Test
    void allExpertsApproved_notifiesAdmins() {
        Map<String, Suggestion.ExpertApprovalEntry> tracker = new HashMap<>();
        for (ExpertRole expert : ExpertRole.reviewOrder()) {
            tracker.put(expert.getDisplayName(), new Suggestion.ExpertApprovalEntry("APPROVED", 1));
        }

        Suggestion suggestion = buildUserGuidanceSuggestion(4L, tracker);

        service.handleExpertClarificationAnswers(4L, "user", buildAnswers("Q?", "A."));

        verify(slackNotificationService).sendApprovalNeededNotification(suggestion);
    }

    // -------------------------------------------------------------------------
    // Some experts not approved → run only non-approved experts
    // -------------------------------------------------------------------------

    @Test
    void someExpertsNotApproved_invokesOnlyNonApprovedExperts() {
        Map<String, Suggestion.ExpertApprovalEntry> tracker = new HashMap<>();
        // All approved except QA_ENGINEER (no entry)
        for (ExpertRole expert : ExpertRole.reviewOrder()) {
            if (expert != ExpertRole.QA_ENGINEER) {
                tracker.put(expert.getDisplayName(), new Suggestion.ExpertApprovalEntry("APPROVED", 1));
            }
        }

        Suggestion suggestion = buildUserGuidanceSuggestion(10L, tracker);

        service.handleExpertClarificationAnswers(10L, "user", buildAnswers("Q?", "A."));

        // QA_ENGINEER (not in tracker) must be invoked
        verify(claudeService, atLeastOnce()).expertReview(
                any(), eq(ExpertRole.QA_ENGINEER.getDisplayName()),
                any(), any(), any(), any(), any(), any(), any(), any(), anyInt(), any());

        // An approved expert must NOT be invoked
        verify(claudeService, never()).expertReview(
                any(), eq(ExpertRole.SECURITY_ENGINEER.getDisplayName()),
                any(), any(), any(), any(), any(), any(), any(), any(), anyInt(), any());
    }

    @Test
    void someExpertsNotApproved_doesNotTransitionToPLANNED() {
        Map<String, Suggestion.ExpertApprovalEntry> tracker = new HashMap<>();
        // Only one expert not approved
        tracker.put(ExpertRole.QA_ENGINEER.getDisplayName(),
                new Suggestion.ExpertApprovalEntry("CHANGES_PROPOSED", 1));
        for (ExpertRole expert : ExpertRole.reviewOrder()) {
            if (expert != ExpertRole.QA_ENGINEER) {
                tracker.put(expert.getDisplayName(), new Suggestion.ExpertApprovalEntry("APPROVED", 1));
            }
        }

        Suggestion suggestion = buildUserGuidanceSuggestion(11L, tracker);

        service.handleExpertClarificationAnswers(11L, "user", buildAnswers("Q?", "A."));

        // Still in EXPERT_REVIEW because the targeted re-review mock returns an incomplete future
        assertThat(suggestion.getStatus()).isEqualTo(SuggestionStatus.EXPERT_REVIEW);
    }

    @Test
    void changesProposedExpert_isIncludedInReReview() {
        Map<String, Suggestion.ExpertApprovalEntry> tracker = new HashMap<>();
        tracker.put(ExpertRole.SOFTWARE_ARCHITECT.getDisplayName(),
                new Suggestion.ExpertApprovalEntry("CHANGES_PROPOSED", 1));
        // All others approved
        for (ExpertRole expert : ExpertRole.reviewOrder()) {
            if (expert != ExpertRole.SOFTWARE_ARCHITECT) {
                tracker.put(expert.getDisplayName(), new Suggestion.ExpertApprovalEntry("APPROVED", 1));
            }
        }

        Suggestion suggestion = buildUserGuidanceSuggestion(12L, tracker);

        service.handleExpertClarificationAnswers(12L, "user", buildAnswers("Q?", "A."));

        verify(claudeService, atLeastOnce()).expertReview(
                any(), eq(ExpertRole.SOFTWARE_ARCHITECT.getDisplayName()),
                any(), any(), any(), any(), any(), any(), any(), any(), anyInt(), any());
    }

    @Test
    void changesRejectedExpert_isIncludedInReReview() {
        Map<String, Suggestion.ExpertApprovalEntry> tracker = new HashMap<>();
        tracker.put(ExpertRole.SECURITY_ENGINEER.getDisplayName(),
                new Suggestion.ExpertApprovalEntry("CHANGES_REJECTED", 1));
        // All others approved
        for (ExpertRole expert : ExpertRole.reviewOrder()) {
            if (expert != ExpertRole.SECURITY_ENGINEER) {
                tracker.put(expert.getDisplayName(), new Suggestion.ExpertApprovalEntry("APPROVED", 1));
            }
        }

        Suggestion suggestion = buildUserGuidanceSuggestion(13L, tracker);

        service.handleExpertClarificationAnswers(13L, "user", buildAnswers("Q?", "A."));

        verify(claudeService, atLeastOnce()).expertReview(
                any(), eq(ExpertRole.SECURITY_ENGINEER.getDisplayName()),
                any(), any(), any(), any(), any(), any(), any(), any(), anyInt(), any());
    }

    // -------------------------------------------------------------------------
    // No tracker entries → all experts re-run
    // -------------------------------------------------------------------------

    @Test
    void noTrackerEntries_runsAllExperts() {
        Suggestion suggestion = buildUserGuidanceSuggestion(20L, new HashMap<>());

        service.handleExpertClarificationAnswers(20L, "user", buildAnswers("Q?", "A."));

        // With no tracker entries, all experts are non-approved → first expert (PROJECT_OWNER) is invoked
        verify(claudeService, atLeastOnce()).expertReview(
                any(), eq(ExpertRole.PROJECT_OWNER.getDisplayName()),
                any(), any(), any(), any(), any(), any(), any(), any(), anyInt(), any());
    }

    @Test
    void noTrackerEntries_doesNotTransitionToPLANNED() {
        Suggestion suggestion = buildUserGuidanceSuggestion(21L, new HashMap<>());

        service.handleExpertClarificationAnswers(21L, "user", buildAnswers("Q?", "A."));

        assertThat(suggestion.getStatus()).isEqualTo(SuggestionStatus.EXPERT_REVIEW);
    }

    // -------------------------------------------------------------------------
    // State updates when targeted re-review is triggered
    // -------------------------------------------------------------------------

    @Test
    void partialApprovals_totalRoundsIncrements() {
        Map<String, Suggestion.ExpertApprovalEntry> tracker = new HashMap<>();
        // Leave one expert without an approved entry
        tracker.put(ExpertRole.QA_ENGINEER.getDisplayName(),
                new Suggestion.ExpertApprovalEntry("CHANGES_PROPOSED", 1));

        Suggestion suggestion = buildUserGuidanceSuggestion(30L, tracker);
        int initialTotal = suggestion.getTotalExpertReviewRounds();

        service.handleExpertClarificationAnswers(30L, "user", buildAnswers("Q?", "A."));

        assertThat(suggestion.getTotalExpertReviewRounds()).isEqualTo(initialTotal + 1);
    }

    @Test
    void partialApprovals_roundResetToOne() {
        Map<String, Suggestion.ExpertApprovalEntry> tracker = new HashMap<>();
        tracker.put(ExpertRole.QA_ENGINEER.getDisplayName(),
                new Suggestion.ExpertApprovalEntry("CHANGES_PROPOSED", 1));

        Suggestion suggestion = buildUserGuidanceSuggestion(31L, tracker);

        service.handleExpertClarificationAnswers(31L, "user", buildAnswers("Q?", "A."));

        assertThat(suggestion.getExpertReviewRound()).isEqualTo(1);
    }

    @Test
    void partialApprovals_planChangedResetToFalse() {
        Map<String, Suggestion.ExpertApprovalEntry> tracker = new HashMap<>();
        tracker.put(ExpertRole.QA_ENGINEER.getDisplayName(),
                new Suggestion.ExpertApprovalEntry("CHANGES_PROPOSED", 1));

        Suggestion suggestion = buildUserGuidanceSuggestion(32L, tracker);
        suggestion.setExpertReviewPlanChanged(true);

        service.handleExpertClarificationAnswers(32L, "user", buildAnswers("Q?", "A."));

        assertThat(suggestion.getExpertReviewPlanChanged()).isFalse();
    }
}
