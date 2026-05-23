package com.sitemanager.service;

import com.sitemanager.model.PlanTask;
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

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * Tests for owner-lock enforcement in handleReviewerResponse.
 * When a non-owner expert's approved changes would remove or alter the scope
 * of tasks locked by the project owner, those changes are blocked.
 */
class SuggestionServiceOwnerLockEnforcementTest {

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

    // ─── helpers ────────────────────────────────────────────────────────────────

    private Suggestion expertReviewSuggestion(int step) {
        Suggestion s = new Suggestion();
        s.setId(1L);
        s.setTitle("Add dark mode");
        s.setDescription("Support dark colour scheme.");
        s.setStatus(SuggestionStatus.EXPERT_REVIEW);
        s.setExpertReviewStep(step);
        s.setExpertReviewRound(1);
        when(suggestionRepository.findById(1L)).thenReturn(Optional.of(s));
        return s;
    }

    private PlanTask planTask(int taskOrder, String displayTitle, String displayDescription) {
        PlanTask t = new PlanTask();
        t.setSuggestionId(1L);
        t.setTaskOrder(taskOrder);
        t.setTitle("tech-title-" + taskOrder);
        t.setDescription("tech-desc-" + taskOrder);
        t.setDisplayTitle(displayTitle);
        t.setDisplayDescription(displayDescription);
        return t;
    }

    /** Reviewer response that says apply=true */
    private static final String REVIEWER_APPLY =
            """
            ```json
            {"apply": true, "notes": "Looks good."}
            ```""";

    /** Reviewer response that says apply=false */
    private static final String REVIEWER_REJECT =
            """
            ```json
            {"apply": false, "notes": "Not needed."}
            ```""";

    // ─── no locked sections ──────────────────────────────────────────────────────

    @Test
    void noLockedSections_appliesChangesNormally() {
        Suggestion s = expertReviewSuggestion(2);
        // No locked sections

        String expertResponse = """
                ```json
                {
                  "status": "CHANGES_PROPOSED",
                  "analysis": "Add caching layer.",
                  "message": "Added caching.",
                  "revisedPlan": "Plan with cache",
                  "revisedTasks": [
                    {"title":"t1","displayTitle":"Cache layer","displayDescription":"Add Redis cache"}
                  ]
                }
                ```""";

        service.handleReviewerResponse(1L, REVIEWER_APPLY, ExpertRole.SOFTWARE_ARCHITECT,
                expertResponse, "Add caching layer.", "Added caching.");

        assertThat(s.getPlanSummary()).isEqualTo("Plan with cache");
        verify(planTaskRepository).deleteBySuggestionId(1L);
        verify(planTaskRepository, atLeastOnce()).save(any(PlanTask.class));
    }

    // ─── locked task removed ─────────────────────────────────────────────────────

    @Test
    void lockedTaskRemoved_allChangesBlocked_treatsAsApproved() {
        Suggestion s = expertReviewSuggestion(2);
        // Task at index 1 is locked; expert proposes only 1 task (index 1 goes out of bounds)
        s.setOwnerLockedSections(List.of(1));
        s.setPlanSummary("Original plan");

        when(planTaskRepository.findBySuggestionIdOrderByTaskOrder(1L))
                .thenReturn(List.of(
                        planTask(1, "Normal task", "Can change"),
                        planTask(2, "Locked task", "Must stay")
                ));

        // Expert proposes only 1 task — locked index 1 is now out of bounds
        String expertResponse = """
                ```json
                {
                  "status": "CHANGES_PROPOSED",
                  "analysis": "Simplified plan.",
                  "message": "Simplified.",
                  "revisedTasks": [
                    {"title":"t1","displayTitle":"Normal task","displayDescription":"Can change"}
                  ]
                }
                ```""";

        service.handleReviewerResponse(1L, REVIEWER_APPLY, ExpertRole.SOFTWARE_ARCHITECT,
                expertResponse, "Simplified plan.", "Simplified.");

        // Plan unchanged because all task changes were blocked
        assertThat(s.getPlanSummary()).isEqualTo("Original plan");
        // No tasks should have been saved via the lock-blocked path
        verify(planTaskRepository, never()).deleteBySuggestionId(anyLong());
        // Not marked as plan changed
        assertThat(s.getExpertReviewPlanChanged()).isNull();
    }

    @Test
    void lockedTaskRemoved_noteExplainsBlock() {
        Suggestion s = expertReviewSuggestion(2);
        // Task at index 1 is locked; expert removes it (proposes only 1 task)
        s.setOwnerLockedSections(List.of(1));

        when(planTaskRepository.findBySuggestionIdOrderByTaskOrder(1L))
                .thenReturn(List.of(
                        planTask(1, "Normal task", "Can change"),
                        planTask(2, "Locked task", "Must stay")
                ));

        String expertResponse = """
                ```json
                {
                  "status": "CHANGES_PROPOSED",
                  "analysis": "Simplified.",
                  "message": "Done.",
                  "revisedTasks": [
                    {"title":"t1","displayTitle":"Normal task","displayDescription":"Can change"}
                  ]
                }
                ```""";

        service.handleReviewerResponse(1L, REVIEWER_APPLY, ExpertRole.SOFTWARE_ARCHITECT,
                expertResponse, "Simplified.", "Done.");

        assertThat(s.getExpertReviewNotes()).contains("blocked");
    }

    @Test
    void lockedTaskRemoved_stepAdvances() {
        Suggestion s = expertReviewSuggestion(2);
        s.setOwnerLockedSections(List.of(0));

        when(planTaskRepository.findBySuggestionIdOrderByTaskOrder(1L))
                .thenReturn(List.of(planTask(1, "Locked", "Essential")));

        String expertResponse = """
                ```json
                {
                  "status": "CHANGES_PROPOSED",
                  "analysis": "Removed all tasks.",
                  "message": "Done.",
                  "revisedTasks": []
                }
                ```""";

        service.handleReviewerResponse(1L, REVIEWER_APPLY, ExpertRole.SOFTWARE_ARCHITECT,
                expertResponse, "Removed all tasks.", "Done.");

        assertThat(s.getExpertReviewStep()).isEqualTo(3);
    }

    // ─── locked task scope changed ───────────────────────────────────────────────

    @Test
    void lockedTaskScopeChanged_displayTitleReverted() {
        Suggestion s = expertReviewSuggestion(2);
        s.setOwnerLockedSections(List.of(0));

        when(planTaskRepository.findBySuggestionIdOrderByTaskOrder(1L))
                .thenReturn(List.of(
                        planTask(1, "Original locked title", "Original locked desc"),
                        planTask(2, "Normal task", "Normal desc")
                ));

        // Expert proposes renaming the locked task's displayTitle
        String expertResponse = """
                ```json
                {
                  "status": "CHANGES_PROPOSED",
                  "analysis": "Renamed locked task.",
                  "message": "Done.",
                  "revisedTasks": [
                    {"title":"t1","displayTitle":"New title CHANGED","displayDescription":"Original locked desc"},
                    {"title":"t2","displayTitle":"Normal task","displayDescription":"Normal desc"}
                  ]
                }
                ```""";

        service.handleReviewerResponse(1L, REVIEWER_APPLY, ExpertRole.SOFTWARE_ARCHITECT,
                expertResponse, "Renamed locked task.", "Done.");

        // Tasks should be saved (scope change is reverted, not all-blocked)
        verify(planTaskRepository).deleteBySuggestionId(1L);

        // The saved task for index 0 should have the original displayTitle
        var savedTasks = mockingDetails(planTaskRepository).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals("save"))
                .map(i -> (PlanTask) i.getArgument(0))
                .toList();
        PlanTask lockedTaskSaved = savedTasks.stream()
                .filter(t -> t.getTaskOrder() == 1)
                .findFirst().orElseThrow();
        assertThat(lockedTaskSaved.getDisplayTitle()).isEqualTo("Original locked title");
    }

    @Test
    void lockedTaskScopeChanged_displayDescriptionReverted() {
        Suggestion s = expertReviewSuggestion(2);
        s.setOwnerLockedSections(List.of(1)); // second task is locked (index 1)

        when(planTaskRepository.findBySuggestionIdOrderByTaskOrder(1L))
                .thenReturn(List.of(
                        planTask(1, "Normal task", "Normal desc"),
                        planTask(2, "Locked task", "Original locked desc")
                ));

        String expertResponse = """
                ```json
                {
                  "status": "CHANGES_PROPOSED",
                  "analysis": "Changed locked task desc.",
                  "message": "Done.",
                  "revisedTasks": [
                    {"title":"t1","displayTitle":"Normal task","displayDescription":"Normal desc"},
                    {"title":"t2","displayTitle":"Locked task","displayDescription":"ALTERED description"}
                  ]
                }
                ```""";

        service.handleReviewerResponse(1L, REVIEWER_APPLY, ExpertRole.SOFTWARE_ARCHITECT,
                expertResponse, "Changed locked task desc.", "Done.");

        var savedTasks = mockingDetails(planTaskRepository).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals("save"))
                .map(i -> (PlanTask) i.getArgument(0))
                .toList();
        PlanTask lockedTaskSaved = savedTasks.stream()
                .filter(t -> t.getTaskOrder() == 2)
                .findFirst().orElseThrow();
        assertThat(lockedTaskSaved.getDisplayDescription()).isEqualTo("Original locked desc");
    }

    @Test
    void lockedTaskScopeChanged_nonLockedTasksStillApplied() {
        Suggestion s = expertReviewSuggestion(2);
        s.setOwnerLockedSections(List.of(0));

        when(planTaskRepository.findBySuggestionIdOrderByTaskOrder(1L))
                .thenReturn(List.of(
                        planTask(1, "Locked title", "Locked desc"),
                        planTask(2, "Normal task", "Old desc")
                ));

        String expertResponse = """
                ```json
                {
                  "status": "CHANGES_PROPOSED",
                  "analysis": "Updated normal task.",
                  "message": "Done.",
                  "revisedTasks": [
                    {"title":"t1","displayTitle":"CHANGED title","displayDescription":"Locked desc"},
                    {"title":"t2","displayTitle":"Normal task","displayDescription":"New improved desc"}
                  ]
                }
                ```""";

        service.handleReviewerResponse(1L, REVIEWER_APPLY, ExpertRole.SOFTWARE_ARCHITECT,
                expertResponse, "Updated normal task.", "Done.");

        var savedTasks = mockingDetails(planTaskRepository).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals("save"))
                .map(i -> (PlanTask) i.getArgument(0))
                .toList();

        // Non-locked task (index 1) should have updated desc
        PlanTask normalTask = savedTasks.stream()
                .filter(t -> t.getTaskOrder() == 2)
                .findFirst().orElseThrow();
        assertThat(normalTask.getDisplayDescription()).isEqualTo("New improved desc");

        // Locked task (index 0) should have reverted title
        PlanTask lockedTask = savedTasks.stream()
                .filter(t -> t.getTaskOrder() == 1)
                .findFirst().orElseThrow();
        assertThat(lockedTask.getDisplayTitle()).isEqualTo("Locked title");
    }

    @Test
    void lockedTaskScopeChanged_planMarkedAsChanged() {
        Suggestion s = expertReviewSuggestion(2);
        s.setOwnerLockedSections(List.of(0));

        when(planTaskRepository.findBySuggestionIdOrderByTaskOrder(1L))
                .thenReturn(List.of(planTask(1, "Locked", "Locked desc")));

        String expertResponse = """
                ```json
                {
                  "status": "CHANGES_PROPOSED",
                  "analysis": "Scope change.",
                  "message": "Done.",
                  "revisedPlan": "Updated plan",
                  "revisedTasks": [
                    {"title":"t1","displayTitle":"Changed title","displayDescription":"Locked desc"}
                  ]
                }
                ```""";

        service.handleReviewerResponse(1L, REVIEWER_APPLY, ExpertRole.SOFTWARE_ARCHITECT,
                expertResponse, "Scope change.", "Done.");

        // Plan summary should be applied (not locked)
        assertThat(s.getPlanSummary()).isEqualTo("Updated plan");
        // Plan marked as changed
        assertThat(s.getExpertReviewPlanChanged()).isTrue();
    }

    @Test
    void lockedTaskScopeChanged_ownerLockNoteAddedToExpertNotes() {
        Suggestion s = expertReviewSuggestion(2);
        s.setOwnerLockedSections(List.of(0));

        when(planTaskRepository.findBySuggestionIdOrderByTaskOrder(1L))
                .thenReturn(List.of(planTask(1, "Protected title", "Protected desc")));

        String expertResponse = """
                ```json
                {
                  "status": "CHANGES_PROPOSED",
                  "analysis": "Changed locked task.",
                  "message": "Done.",
                  "revisedTasks": [
                    {"title":"t1","displayTitle":"Changed title","displayDescription":"Protected desc"}
                  ]
                }
                ```""";

        service.handleReviewerResponse(1L, REVIEWER_APPLY, ExpertRole.SOFTWARE_ARCHITECT,
                expertResponse, "Changed locked task.", "Done.");

        assertThat(s.getExpertReviewNotes()).contains("owner-protected");
    }

    // ─── implementation-level changes to locked task are allowed ─────────────────

    @Test
    void lockedTaskImplementationFieldChanged_allowsChange() {
        Suggestion s = expertReviewSuggestion(2);
        s.setOwnerLockedSections(List.of(0));

        when(planTaskRepository.findBySuggestionIdOrderByTaskOrder(1L))
                .thenReturn(List.of(planTask(1, "Locked display title", "Locked display desc")));

        // Expert changes `title` (technical field) and `estimatedMinutes` — not displayTitle/displayDescription
        String expertResponse = """
                ```json
                {
                  "status": "CHANGES_PROPOSED",
                  "analysis": "Optimized implementation.",
                  "message": "Done.",
                  "revisedTasks": [
                    {
                      "title": "new-tech-title",
                      "description": "new-tech-desc",
                      "displayTitle": "Locked display title",
                      "displayDescription": "Locked display desc",
                      "estimatedMinutes": 90
                    }
                  ]
                }
                ```""";

        service.handleReviewerResponse(1L, REVIEWER_APPLY, ExpertRole.SOFTWARE_ARCHITECT,
                expertResponse, "Optimized implementation.", "Done.");

        // No blocking — implementation fields may change freely
        var savedTasks = mockingDetails(planTaskRepository).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals("save"))
                .map(i -> (PlanTask) i.getArgument(0))
                .toList();
        PlanTask saved = savedTasks.stream().filter(t -> t.getTaskOrder() == 1).findFirst().orElseThrow();
        assertThat(saved.getTitle()).isEqualTo("new-tech-title");
        assertThat(saved.getEstimatedMinutes()).isEqualTo(90);
        // Display fields unchanged (same as current)
        assertThat(saved.getDisplayTitle()).isEqualTo("Locked display title");
        // Plan was marked changed
        assertThat(s.getExpertReviewPlanChanged()).isTrue();
        // No "owner lock" note in notes (no block occurred)
        assertThat(s.getExpertReviewNotes()).doesNotContain("owner-protected");
    }

    // ─── reviewer rejects — owner lock not evaluated ──────────────────────────────

    @Test
    void reviewerRejects_ownerLockNotEvaluated() {
        Suggestion s = expertReviewSuggestion(2);
        s.setOwnerLockedSections(List.of(0));
        s.setPlanSummary("Existing plan");

        when(planTaskRepository.findBySuggestionIdOrderByTaskOrder(1L))
                .thenReturn(List.of(planTask(1, "Locked", "Essential")));

        String expertResponse = """
                ```json
                {
                  "status": "CHANGES_PROPOSED",
                  "analysis": "Removed tasks.",
                  "message": "Done.",
                  "revisedTasks": []
                }
                ```""";

        service.handleReviewerResponse(1L, REVIEWER_REJECT, ExpertRole.SOFTWARE_ARCHITECT,
                expertResponse, "Removed tasks.", "Done.");

        // Reviewer rejected — no changes applied, plan untouched
        assertThat(s.getPlanSummary()).isEqualTo("Existing plan");
        verify(planTaskRepository, never()).deleteBySuggestionId(anyLong());
    }

    // ─── locked task removal with plan-level changes ──────────────────────────────

    @Test
    void lockedTaskRemoved_butPlanSummaryChanges_appliesPlanNotTasks() {
        Suggestion s = expertReviewSuggestion(2);
        // Task at index 1 is locked; expert proposes only 1 task (removes locked index 1)
        s.setOwnerLockedSections(List.of(1));
        s.setPlanSummary("Old plan");

        when(planTaskRepository.findBySuggestionIdOrderByTaskOrder(1L))
                .thenReturn(List.of(
                        planTask(1, "Normal", "Normal"),
                        planTask(2, "Locked task", "Must stay")
                ));

        // Expert proposes removing locked task (index 1 out of bounds) but also updates revisedPlan
        String expertResponse = """
                ```json
                {
                  "status": "CHANGES_PROPOSED",
                  "analysis": "Updated plan and simplified tasks.",
                  "message": "Done.",
                  "revisedPlan": "New plan summary",
                  "revisedTasks": [
                    {"title":"t1","displayTitle":"Normal","displayDescription":"Normal"}
                  ]
                }
                ```""";

        service.handleReviewerResponse(1L, REVIEWER_APPLY, ExpertRole.SOFTWARE_ARCHITECT,
                expertResponse, "Updated plan and simplified tasks.", "Done.");

        // Plan summary change should be applied
        assertThat(s.getPlanSummary()).isEqualTo("New plan summary");
        // But task changes (which would remove the locked task) should NOT be applied
        verify(planTaskRepository, never()).deleteBySuggestionId(anyLong());
    }
}
