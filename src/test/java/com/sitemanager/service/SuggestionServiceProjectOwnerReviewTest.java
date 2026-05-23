package com.sitemanager.service;

import com.sitemanager.model.Suggestion;
import com.sitemanager.model.SuggestionMessage;
import com.sitemanager.model.enums.ExpertRole;
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

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

class SuggestionServiceProjectOwnerReviewTest {

    private SuggestionRepository suggestionRepository;
    private SuggestionMessageRepository messageRepository;
    private PlanTaskRepository planTaskRepository;
    private SuggestionWebSocketHandler webSocketHandler;
    private UserNotificationWebSocketHandler userNotificationHandler;
    private SlackNotificationService slackNotificationService;
    private ClaudeService claudeService;
    private ExpertReviewService service;

    @BeforeEach
    void setUp() {
        suggestionRepository = mock(SuggestionRepository.class);
        messageRepository = mock(SuggestionMessageRepository.class);
        planTaskRepository = mock(PlanTaskRepository.class);
        webSocketHandler = mock(SuggestionWebSocketHandler.class);
        userNotificationHandler = mock(UserNotificationWebSocketHandler.class);
        slackNotificationService = mock(SlackNotificationService.class);
        claudeService = mock(ClaudeService.class);

        when(slackNotificationService.sendNotification(any(), any()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(slackNotificationService.sendApprovalNeededNotification(any()))
                .thenReturn(CompletableFuture.completedFuture(null));

        UserRepository userRepository = mock(UserRepository.class);
        when(userRepository.findByRole(any())).thenReturn(List.of());

        SuggestionMessage savedMsg = mock(SuggestionMessage.class);
        when(savedMsg.getId()).thenReturn(0L);
        when(messageRepository.save(any())).thenReturn(savedMsg);

        when(suggestionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // Prevent NPE when the pipeline advances to the next batch and calls expertReview
        when(planTaskRepository.findBySuggestionIdOrderByTaskOrder(any()))
                .thenReturn(List.of());
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

    private Suggestion expertReviewSuggestion(int step) {
        Suggestion s = new Suggestion();
        s.setId(1L);
        s.setTitle("Add dark mode");
        s.setDescription("Support a dark colour scheme throughout the app.");
        s.setStatus(SuggestionStatus.EXPERT_REVIEW);
        s.setExpertReviewStep(step);
        s.setExpertReviewRound(1);
        when(suggestionRepository.findById(1L)).thenReturn(Optional.of(s));
        return s;
    }

    // ── applyProjectOwnerChanges via handleExpertReviewResponse ──────────────

    @Test
    void projectOwner_changesProposed_appliesRevisedPlan() {
        Suggestion s = expertReviewSuggestion(0);

        String response = """
                ```json
                {
                  "status": "CHANGES_PROPOSED",
                  "analysis": "The plan is missing the user settings screen described in the suggestion.",
                  "message": "I have added the missing settings screen to the plan.",
                  "revisedPlan": "Updated plan with settings screen",
                  "lockedTaskIndices": [0, 1]
                }
                ```""";

        service.handleExpertReviewResponse(1L, response, ExpertRole.PROJECT_OWNER, "session-1");

        assertThat(s.getPlanSummary()).isEqualTo("Updated plan with settings screen");
    }

    @Test
    void projectOwner_changesProposed_storesLockedTaskIndices() {
        Suggestion s = expertReviewSuggestion(0);

        String response = """
                ```json
                {
                  "status": "CHANGES_PROPOSED",
                  "analysis": "Added missing tasks. Tasks 0 and 2 are essential to fulfilling the original request.",
                  "message": "Plan updated.",
                  "lockedTaskIndices": [0, 2]
                }
                ```""";

        service.handleExpertReviewResponse(1L, response, ExpertRole.PROJECT_OWNER, "session-1");

        List<Integer> locked = s.getOwnerLockedSections();
        assertThat(locked).containsExactly(0, 2);
    }

    @Test
    void projectOwner_changesProposed_setsExpertReviewPlanChanged() {
        Suggestion s = expertReviewSuggestion(0);

        String response = """
                ```json
                {
                  "status": "CHANGES_PROPOSED",
                  "analysis": "Plan updated to include all requested features from the original suggestion.",
                  "message": "Changes applied.",
                  "lockedTaskIndices": [1]
                }
                ```""";

        service.handleExpertReviewResponse(1L, response, ExpertRole.PROJECT_OWNER, "session-1");

        assertThat(s.getExpertReviewPlanChanged()).isTrue();
    }

    @Test
    void projectOwner_changesProposed_marksOwnerDomainChanged() {
        Suggestion s = expertReviewSuggestion(0);

        String response = """
                ```json
                {
                  "status": "CHANGES_PROPOSED",
                  "analysis": "Added missing tasks to ensure the plan covers the complete request scope.",
                  "message": "Plan updated.",
                  "lockedTaskIndices": []
                }
                ```""";

        service.handleExpertReviewResponse(1L, response, ExpertRole.PROJECT_OWNER, "session-1");

        assertThat(s.getExpertReviewChangedDomains()).contains("OWNER");
    }

    @Test
    void projectOwner_changesProposed_advancesExpertStep() {
        Suggestion s = expertReviewSuggestion(0);

        String response = """
                ```json
                {
                  "status": "CHANGES_PROPOSED",
                  "analysis": "Plan updated to capture all requirements described in the original suggestion.",
                  "message": "Done.",
                  "lockedTaskIndices": [0]
                }
                ```""";

        service.handleExpertReviewResponse(1L, response, ExpertRole.PROJECT_OWNER, "session-1");

        assertThat(s.getExpertReviewStep()).isEqualTo(1);
    }

    @Test
    void projectOwner_changesProposed_noLockedTaskIndicesInResponse_leavesLockedSectionsEmpty() {
        Suggestion s = expertReviewSuggestion(0);

        String response = """
                ```json
                {
                  "status": "CHANGES_PROPOSED",
                  "analysis": "Minor wording adjustments made to improve clarity of task descriptions.",
                  "message": "Plan updated."
                }
                ```""";

        service.handleExpertReviewResponse(1L, response, ExpertRole.PROJECT_OWNER, "session-1");

        assertThat(s.getOwnerLockedSections()).isEmpty();
    }

    @Test
    void projectOwner_changesProposed_doesNotRouteToChangeReviewer() {
        expertReviewSuggestion(0);

        String response = """
                ```json
                {
                  "status": "CHANGES_PROPOSED",
                  "analysis": "Plan adjusted to capture the full scope of the original user request.",
                  "message": "Done.",
                  "lockedTaskIndices": [0]
                }
                ```""";

        service.handleExpertReviewResponse(1L, response, ExpertRole.PROJECT_OWNER, "session-1");

        // PROJECT_OWNER changes are applied directly — no reviewExpertFeedback call
        verify(claudeService, never()).reviewExpertFeedback(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), anyInt(), any());
    }

    @Test
    void projectOwner_approved_advancesStepWithoutChangingPlan() {
        Suggestion s = expertReviewSuggestion(0);
        s.setPlanSummary("Original plan");

        String response = """
                ```json
                {
                  "status": "APPROVED",
                  "analysis": "The plan fully and accurately captures the user request. No changes needed."
                }
                ```""";

        service.handleExpertReviewResponse(1L, response, ExpertRole.PROJECT_OWNER, "session-1");

        assertThat(s.getPlanSummary()).isEqualTo("Original plan");
        assertThat(s.getExpertReviewStep()).isEqualTo(1);
        assertThat(s.getExpertReviewPlanChanged()).isNull();
    }

    // ── step 0 is PROJECT_OWNER ───────────────────────────────────────────────

    @Test
    void reviewOrder_firstStepIsProjectOwner() {
        assertThat(ExpertRole.fromStep(0)).isEqualTo(ExpertRole.PROJECT_OWNER);
    }

    @Test
    void reviewOrder_totalStepsIsCorrect() {
        assertThat(ExpertRole.reviewOrder()).hasSize(12);
    }

    @Test
    void reviewBatches_batchZeroContainsOnlyProjectOwner() {
        ExpertRole[][] batches = ExpertRole.reviewBatches();
        assertThat(batches[0]).containsExactly(ExpertRole.PROJECT_OWNER);
    }

    // ── getExpertReviewStatus reflects PROJECT_OWNER at step 0 ───────────────

    @Test
    void getExpertReviewStatus_totalStepsIsCorrect() {
        Suggestion s = expertReviewSuggestion(0);
        s.setExpertReviewStep(0);

        java.util.Map<String, Object> status = service.getExpertReviewStatus(1L);

        assertThat(status).isNotNull();
        assertThat(status.get("totalSteps")).isEqualTo(12);
    }

    @Test
    void getExpertReviewStatus_firstExpertIsProjectOwner() {
        Suggestion s = expertReviewSuggestion(0);
        s.setExpertReviewStep(0);

        java.util.Map<String, Object> status = service.getExpertReviewStatus(1L);

        @SuppressWarnings("unchecked")
        java.util.List<java.util.Map<String, String>> experts =
                (java.util.List<java.util.Map<String, String>>) status.get("experts");
        assertThat(experts.get(0).get("name")).isEqualTo("Project Owner");
        assertThat(experts.get(0).get("status")).isEqualTo("in_progress");
    }

    @Test
    void getExpertReviewStatus_includesOwnerLockedSections_whenSet() {
        Suggestion s = expertReviewSuggestion(1);
        s.setOwnerLockedSections(java.util.List.of(0, 2, 4));

        java.util.Map<String, Object> status = service.getExpertReviewStatus(1L);

        @SuppressWarnings("unchecked")
        java.util.List<Integer> locked = (java.util.List<Integer>) status.get("ownerLockedSections");
        assertThat(locked).containsExactly(0, 2, 4);
    }

    @Test
    void getExpertReviewStatus_ownerLockedSections_emptyWhenNotSet() {
        Suggestion s = expertReviewSuggestion(1);
        // No owner-locked sections set

        java.util.Map<String, Object> status = service.getExpertReviewStatus(1L);

        @SuppressWarnings("unchecked")
        java.util.List<Integer> locked = (java.util.List<Integer>) status.get("ownerLockedSections");
        assertThat(locked).isNotNull().isEmpty();
    }

    // ── getReviewSummary reflects PROJECT_OWNER and blockedByOwnerLock ────────

    @Test
    void getReviewSummary_firstEntryIsProjectOwner() {
        Suggestion s = expertReviewSuggestion(0);
        // No review notes yet

        java.util.List<java.util.Map<String, Object>> summary = service.getReviewSummary(1L);

        assertThat(summary).isNotEmpty();
        assertThat(summary.get(0).get("expertName")).isEqualTo("Project Owner");
        assertThat(summary.get(0).get("status")).isEqualTo("PENDING");
    }

    @Test
    void getReviewSummary_projectOwner_showsReviewedStatus_whenNotePresent() {
        Suggestion s = expertReviewSuggestion(1);
        s.setExpertReviewNotes("**Project Owner**: The plan fully captures the original request. All key requirements are addressed.");

        java.util.List<java.util.Map<String, Object>> summary = service.getReviewSummary(1L);

        assertThat(summary.get(0).get("expertName")).isEqualTo("Project Owner");
        assertThat(summary.get(0).get("status")).isEqualTo("APPROVED");
        assertThat(summary.get(0).get("keyPoint")).isNotNull();
    }

    @Test
    void getReviewSummary_blockedByOwnerLock_isTrueWhenNoteContainsOwnerLockText() {
        Suggestion s = expertReviewSuggestion(3);
        s.setExpertReviewNotes(
            "**Project Owner**: Plan is complete and addresses all requirements.\n\n" +
            "**Software Architect**: Proposed changes — not applied. All task changes were blocked by owner-lock constraints. Some tasks could not be removed.\nReviewer: Changes rejected."
        );

        java.util.List<java.util.Map<String, Object>> summary = service.getReviewSummary(1L);

        java.util.Map<String, Object> architectEntry = summary.stream()
            .filter(e -> "Software Architect".equals(e.get("expertName")))
            .findFirst().orElseThrow();
        assertThat(architectEntry.get("blockedByOwnerLock")).isEqualTo(true);
    }

    @Test
    void getReviewSummary_blockedByOwnerLock_isAbsentWhenNoOwnerLockInNote() {
        Suggestion s = expertReviewSuggestion(2);
        s.setExpertReviewNotes(
            "**Project Owner**: Plan is complete.\n\n" +
            "**Software Architect**: The plan looks solid and well-structured."
        );

        java.util.List<java.util.Map<String, Object>> summary = service.getReviewSummary(1L);

        java.util.Map<String, Object> architectEntry = summary.stream()
            .filter(e -> "Software Architect".equals(e.get("expertName")))
            .findFirst().orElseThrow();
        assertThat(architectEntry.containsKey("blockedByOwnerLock")).isFalse();
    }

    @Test
    void getReviewSummary_totalEntriesMatchesReviewOrder() {
        Suggestion s = expertReviewSuggestion(0);

        java.util.List<java.util.Map<String, Object>> summary = service.getReviewSummary(1L);

        assertThat(summary).hasSize(ExpertRole.reviewOrder().length);
    }
}
