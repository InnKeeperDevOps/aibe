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

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

/**
 * Tests for the approved-expert filter in runNextExpertReview:
 * experts who previously approved are excluded from targeted re-reviews
 * unless their own domain was directly changed.
 */
class SuggestionServiceApprovedExpertFilterTest {

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
        SiteSettingsService siteSettingsService = mock(SiteSettingsService.class);
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

        SuggestionMessagingHelper messagingHelper = new SuggestionMessagingHelper(
                suggestionRepository,
                messageRepository,
                planTaskRepository,
                webSocketHandler,
                userNotificationHandler,
                slackNotificationService,
                siteSettingsService
        );

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
                mock(ExpertReviewCostRepository.class),
                mock(CostRollupService.class),
                spendingLimitService,
                mock(SpendingAlertService.class)
        );
    }

    /**
     * Builds a suggestion ready for the "all experts reviewed, plan changed" branch
     * in runNextExpertReview.
     */
    private Suggestion buildSuggestionForReReview(long id, String changedDomains,
                                                   Map<String, Suggestion.ExpertApprovalEntry> tracker) {
        Suggestion s = new Suggestion();
        s.setId(id);
        s.setStatus(SuggestionStatus.EXPERT_REVIEW);
        s.setExpertReviewStep(ExpertRole.reviewOrder().length); // past the end → all experts done
        s.setExpertReviewRound(1);                             // round 1 < MAX (2)
        s.setTotalExpertReviewRounds(1);                       // total 1 < MAX (3)
        s.setExpertReviewPlanChanged(true);
        s.setExpertReviewChangedDomains(changedDomains);
        if (tracker != null) {
            s.setExpertApprovalMap(tracker);
        }
        when(suggestionRepository.findById(id)).thenReturn(Optional.of(s));
        return s;
    }

    // -------------------------------------------------------------------------
    // Filter: approved experts with non-changed domain are excluded
    // -------------------------------------------------------------------------

    /**
     * SECURITY changed → affectedDomains = {SECURITY, ARCHITECTURE}.
     * ARCHITECTURE experts (SOFTWARE_ARCHITECT, INFRASTRUCTURE_ENGINEER) previously approved.
     * ARCHITECTURE is NOT in changedDomains → they should be excluded from the re-review.
     * SECURITY_ENGINEER is in changedDomains → always included regardless of approval status.
     */
    @Test
    void approvedDependentExpertsAreExcludedFromReReview() {
        Map<String, Suggestion.ExpertApprovalEntry> tracker = new HashMap<>();
        tracker.put(ExpertRole.SOFTWARE_ARCHITECT.getDisplayName(),
                new Suggestion.ExpertApprovalEntry("APPROVED", 1));
        tracker.put(ExpertRole.INFRASTRUCTURE_ENGINEER.getDisplayName(),
                new Suggestion.ExpertApprovalEntry("APPROVED", 1));

        Suggestion suggestion = buildSuggestionForReReview(1L, "SECURITY", tracker);

        invokeRunNextExpertReview(1L);

        // SECURITY_ENGINEER (changedDomain) must be in the re-review list →
        // claudeService.expertReview is called at least once
        verify(claudeService, atLeastOnce()).expertReview(
                any(), eq(ExpertRole.SECURITY_ENGINEER.getDisplayName()),
                any(), any(), any(), any(), any(), any(), any(), any(), anyInt(), any());

        // ARCHITECTURE experts should NOT be re-invoked
        verify(claudeService, never()).expertReview(
                any(), eq(ExpertRole.SOFTWARE_ARCHITECT.getDisplayName()),
                any(), any(), any(), any(), any(), any(), any(), any(), anyInt(), any());
        verify(claudeService, never()).expertReview(
                any(), eq(ExpertRole.INFRASTRUCTURE_ENGINEER.getDisplayName()),
                any(), any(), any(), any(), any(), any(), any(), any(), anyInt(), any());
    }

    /**
     * An expert with CHANGES_PROPOSED status (not APPROVED) whose domain is NOT in changedDomains
     * should still be included in the re-review — only APPROVED experts are skipped.
     */
    @Test
    void notApprovedDependentExpertsAreIncludedInReReview() {
        Map<String, Suggestion.ExpertApprovalEntry> tracker = new HashMap<>();
        // SOFTWARE_ARCHITECT previously proposed changes (not approved)
        tracker.put(ExpertRole.SOFTWARE_ARCHITECT.getDisplayName(),
                new Suggestion.ExpertApprovalEntry("CHANGES_PROPOSED", 1));

        // SECURITY changed → affectedDomains includes ARCHITECTURE (SOFTWARE_ARCHITECT's domain)
        Suggestion suggestion = buildSuggestionForReReview(2L, "SECURITY", tracker);

        invokeRunNextExpertReview(2L);

        // SOFTWARE_ARCHITECT has CHANGES_PROPOSED, not APPROVED → must still be re-invoked
        verify(claudeService, atLeastOnce()).expertReview(
                any(), eq(ExpertRole.SOFTWARE_ARCHITECT.getDisplayName()),
                any(), any(), any(), any(), any(), any(), any(), any(), anyInt(), any());
    }

    /**
     * An expert with CHANGES_REJECTED status whose domain is NOT in changedDomains
     * should be included — CHANGES_REJECTED means they flagged unresolved concerns
     * and must never be skipped.
     */
    @Test
    void changesRejectedDependentExpertsAreNeverSkipped() {
        Map<String, Suggestion.ExpertApprovalEntry> tracker = new HashMap<>();
        tracker.put(ExpertRole.SOFTWARE_ARCHITECT.getDisplayName(),
                new Suggestion.ExpertApprovalEntry("CHANGES_REJECTED", 1));

        Suggestion suggestion = buildSuggestionForReReview(3L, "SECURITY", tracker);

        invokeRunNextExpertReview(3L);

        // CHANGES_REJECTED expert must always be included
        verify(claudeService, atLeastOnce()).expertReview(
                any(), eq(ExpertRole.SOFTWARE_ARCHITECT.getDisplayName()),
                any(), any(), any(), any(), any(), any(), any(), any(), anyInt(), any());
    }

    /**
     * An expert whose own domain IS in changedDomains should always be included,
     * even if they have APPROVED status.
     * All ARCHITECTURE (dependent) experts are pre-approved so they are filtered out,
     * leaving SECURITY_ENGINEER as the only expert in the re-run list.
     */
    @Test
    void approvedExpertWhoseOwnDomainChangedIsStillIncluded() {
        Map<String, Suggestion.ExpertApprovalEntry> tracker = new HashMap<>();
        // SECURITY_ENGINEER approved but their domain IS in changedDomains → must NOT be excluded
        tracker.put(ExpertRole.SECURITY_ENGINEER.getDisplayName(),
                new Suggestion.ExpertApprovalEntry("APPROVED", 1));
        // All dependent ARCHITECTURE experts approved → excluded (domain not in changedDomains)
        tracker.put(ExpertRole.SOFTWARE_ARCHITECT.getDisplayName(),
                new Suggestion.ExpertApprovalEntry("APPROVED", 1));
        tracker.put(ExpertRole.INFRASTRUCTURE_ENGINEER.getDisplayName(),
                new Suggestion.ExpertApprovalEntry("APPROVED", 1));

        Suggestion suggestion = buildSuggestionForReReview(4L, "SECURITY", tracker);

        invokeRunNextExpertReview(4L);

        // Only SECURITY_ENGINEER in list (dependent experts filtered, SECURITY_ENGINEER kept
        // because ownDomainChanged=true even though APPROVED)
        verify(claudeService, atLeastOnce()).expertReview(
                any(), eq(ExpertRole.SECURITY_ENGINEER.getDisplayName()),
                any(), any(), any(), any(), any(), any(), any(), any(), anyInt(), any());
    }

    /**
     * An expert with no tracker entry (null status) whose domain is in affectedDomains
     * should be included — only explicitly APPROVED experts are skipped.
     * With SECURITY as changedDomain, the first expert in reviewOrder() that falls in
     * {SECURITY, ARCHITECTURE} is SOFTWARE_ARCHITECT — assert it is invoked.
     */
    @Test
    void expertWithNoTrackerEntryIsIncludedInReReview() {
        // Empty tracker — no experts have any recorded status
        Suggestion suggestion = buildSuggestionForReReview(5L, "SECURITY", new HashMap<>());

        invokeRunNextExpertReview(5L);

        // SOFTWARE_ARCHITECT (first in reviewOrder() with domain in affectedDomains) is invoked
        verify(claudeService, atLeastOnce()).expertReview(
                any(), eq(ExpertRole.SOFTWARE_ARCHITECT.getDisplayName()),
                any(), any(), any(), any(), any(), any(), any(), any(), anyInt(), any());
    }

    // -------------------------------------------------------------------------
    // Short-circuit: empty filtered list → transition to PLANNED
    // -------------------------------------------------------------------------

    /**
     * When changedDomains is empty (or null), affectedDomains will also be empty,
     * causing no experts to be selected. The re-review must be skipped entirely
     * and the suggestion must transition to PLANNED with the expected system message.
     */
    @Test
    void emptyFilteredExpertList_transitionsToPLANNED() {
        // Empty changedDomains → empty affectedDomains → no experts to re-run
        Suggestion suggestion = buildSuggestionForReReview(10L, null, new HashMap<>());

        invokeRunNextExpertReview(10L);

        assertThat(suggestion.getStatus()).isEqualTo(SuggestionStatus.PLANNED);
        assertThat(suggestion.getCurrentPhase()).isEqualTo("Plan ready — waiting for approval");
    }

    @Test
    void emptyFilteredExpertList_clearsReviewFields() {
        Suggestion suggestion = buildSuggestionForReReview(11L, null, new HashMap<>());

        invokeRunNextExpertReview(11L);

        assertThat(suggestion.getExpertReviewStep()).isNull();
        assertThat(suggestion.getExpertReviewRound()).isNull();
        assertThat(suggestion.getExpertReviewPlanChanged()).isNull();
        assertThat(suggestion.getTotalExpertReviewRounds()).isNull();
        assertThat(suggestion.getExpertReviewChangedDomains()).isNull();
    }

    @Test
    void emptyFilteredExpertList_doesNotInvokeAnyExpert() {
        Suggestion suggestion = buildSuggestionForReReview(12L, null, new HashMap<>());

        invokeRunNextExpertReview(12L);

        verify(claudeService, never()).expertReview(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), anyInt(), any());
    }

    @Test
    void emptyFilteredExpertList_addsExpectedSystemMessage() {
        Suggestion suggestion = buildSuggestionForReReview(13L, null, new HashMap<>());

        invokeRunNextExpertReview(13L);

        // System message should be saved via messageRepository
        verify(messageRepository, atLeastOnce()).save(argThat(msg -> {
            SuggestionMessage sm = (SuggestionMessage) msg;
            return sm.getContent().contains("All affected reviewers already approved");
        }));
    }

    @Test
    void emptyFilteredExpertList_notifiesAdmins() {
        Suggestion suggestion = buildSuggestionForReReview(14L, null, new HashMap<>());

        invokeRunNextExpertReview(14L);

        verify(slackNotificationService).sendApprovalNeededNotification(suggestion);
    }

    // -------------------------------------------------------------------------
    // Mixed: some approved (filtered), some not — non-empty list still proceeds
    // -------------------------------------------------------------------------

    /**
     * SECURITY changed. ARCHITECTURE experts approved (should be filtered out).
     * SECURITY_ENGINEER has no tracker entry (should be included).
     * The re-review must proceed with only SECURITY_ENGINEER.
     */
    @Test
    void mixedTracker_onlyNonApprovedExpertsReRun() {
        Map<String, Suggestion.ExpertApprovalEntry> tracker = new HashMap<>();
        tracker.put(ExpertRole.SOFTWARE_ARCHITECT.getDisplayName(),
                new Suggestion.ExpertApprovalEntry("APPROVED", 1));
        tracker.put(ExpertRole.INFRASTRUCTURE_ENGINEER.getDisplayName(),
                new Suggestion.ExpertApprovalEntry("APPROVED", 1));
        // SECURITY_ENGINEER has no entry → will be included

        Suggestion suggestion = buildSuggestionForReReview(20L, "SECURITY", tracker);

        invokeRunNextExpertReview(20L);

        // Must still be EXPERT_REVIEW (not PLANNED), because SECURITY_ENGINEER still needs to run
        // (the claudeService mock returns an incomplete future, so re-review is in progress)
        // We verify SECURITY_ENGINEER was invoked and ARCHITECTURE experts were not
        verify(claudeService, atLeastOnce()).expertReview(
                any(), eq(ExpertRole.SECURITY_ENGINEER.getDisplayName()),
                any(), any(), any(), any(), any(), any(), any(), any(), anyInt(), any());
        verify(claudeService, never()).expertReview(
                any(), eq(ExpertRole.SOFTWARE_ARCHITECT.getDisplayName()),
                any(), any(), any(), any(), any(), any(), any(), any(), anyInt(), any());
        verify(claudeService, never()).expertReview(
                any(), eq(ExpertRole.INFRASTRUCTURE_ENGINEER.getDisplayName()),
                any(), any(), any(), any(), any(), any(), any(), any(), anyInt(), any());
    }

    // -------------------------------------------------------------------------
    // Reflection helper
    // -------------------------------------------------------------------------

    private void invokeRunNextExpertReview(Long suggestionId) {
        try {
            Method m = ExpertReviewService.class.getDeclaredMethod("runNextExpertReview", Long.class);
            m.setAccessible(true);
            m.invoke(service, suggestionId);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
