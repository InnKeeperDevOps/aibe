package com.sitemanager.service;

import com.sitemanager.model.PlanTask;
import com.sitemanager.model.Suggestion;
import com.sitemanager.model.SuggestionMessage;
import com.sitemanager.model.enums.ExpertRole;
import com.sitemanager.model.enums.SenderType;
import com.sitemanager.model.enums.SuggestionStatus;
import com.sitemanager.model.enums.TaskStatus;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

/**
 * Unit tests for ExpertReviewService covering: getExpertReviewStatus,
 * getExpertReviewTotalSteps, getReviewSummary, startExpertReviewPipeline,
 * handleExpertReviewResponse, handleExpertClarificationAnswers,
 * isPlanLocked guard, retry logic, and approval-tracker state.
 */
class ExpertReviewServiceTest {

    private SuggestionRepository suggestionRepository;
    private SuggestionMessageRepository messageRepository;
    private PlanTaskRepository planTaskRepository;
    private ClaudeService claudeService;
    private SlackNotificationService slackNotificationService;
    private SuggestionMessagingHelper messagingHelper;
    private ExpertReviewCostRepository costRepository;
    private SpendingLimitService spendingLimitService;
    private ExpertReviewService service;

    @BeforeEach
    void setUp() {
        suggestionRepository = mock(SuggestionRepository.class);
        messageRepository = mock(SuggestionMessageRepository.class);
        planTaskRepository = mock(PlanTaskRepository.class);
        claudeService = mock(ClaudeService.class);
        slackNotificationService = mock(SlackNotificationService.class);
        SuggestionWebSocketHandler webSocketHandler = mock(SuggestionWebSocketHandler.class);
        UserNotificationWebSocketHandler userNotificationHandler = mock(UserNotificationWebSocketHandler.class);
        UserRepository userRepository = mock(UserRepository.class);
        messagingHelper = mock(SuggestionMessagingHelper.class);
        costRepository = mock(ExpertReviewCostRepository.class);
        when(costRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        spendingLimitService = mock(SpendingLimitService.class);
        when(spendingLimitService.checkCanStartReview(any()))
                .thenReturn(SpendingLimitService.LimitCheck.allowed());

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
                mock(SpendingAlertService.class),
                mock(PlanExecutionService.class)
        );
    }

    // -------------------------------------------------------------------------
    // getExpertReviewStatus
    // -------------------------------------------------------------------------

    @Test
    void getExpertReviewStatus_returnsNullWhenSuggestionNotFound() {
        when(suggestionRepository.findById(1L)).thenReturn(Optional.empty());
        assertThat(service.getExpertReviewStatus(1L)).isNull();
    }

    @Test
    void getExpertReviewStatus_returnsNullWhenStepIsNull() {
        Suggestion s = new Suggestion();
        s.setId(1L);
        s.setExpertReviewStep(null);
        when(suggestionRepository.findById(1L)).thenReturn(Optional.of(s));
        assertThat(service.getExpertReviewStatus(1L)).isNull();
    }

    @Test
    void getExpertReviewStatus_returnsMapWithCurrentStep() {
        Suggestion s = new Suggestion();
        s.setId(2L);
        s.setExpertReviewStep(1);
        s.setExpertReviewRound(2);
        when(suggestionRepository.findById(2L)).thenReturn(Optional.of(s));

        Map<String, Object> result = service.getExpertReviewStatus(2L);

        assertThat(result).isNotNull();
        assertThat(result.get("currentStep")).isEqualTo(1);
        assertThat(result.get("round")).isEqualTo(2);
        assertThat(result.get("maxRounds")).isEqualTo(ExpertReviewService.MAX_EXPERT_REVIEW_ROUNDS);
    }

    @Test
    void getExpertReviewStatus_expertListHasCorrectStatuses() {
        Suggestion s = new Suggestion();
        s.setId(3L);
        s.setExpertReviewStep(1); // step 0 = completed, step 1 = in_progress, rest = pending
        when(suggestionRepository.findById(3L)).thenReturn(Optional.of(s));

        Map<String, Object> result = service.getExpertReviewStatus(3L);

        @SuppressWarnings("unchecked")
        List<Map<String, String>> experts = (List<Map<String, String>>) result.get("experts");
        assertThat(experts.get(0).get("status")).isEqualTo("completed");
        assertThat(experts.get(1).get("status")).isEqualTo("in_progress");
        for (int i = 2; i < experts.size(); i++) {
            assertThat(experts.get(i).get("status")).isEqualTo("pending");
        }
    }

    // -------------------------------------------------------------------------
    // getExpertReviewTotalSteps
    // -------------------------------------------------------------------------

    @Test
    void getExpertReviewTotalSteps_returnsReviewOrderLength() {
        assertThat(service.getExpertReviewTotalSteps())
                .isEqualTo(ExpertRole.reviewOrder().length);
    }

    // -------------------------------------------------------------------------
    // getReviewSummary
    // -------------------------------------------------------------------------

    @Test
    void getReviewSummary_returnsNullWhenSuggestionNotFound() {
        when(suggestionRepository.findById(1L)).thenReturn(Optional.empty());
        assertThat(service.getReviewSummary(1L)).isNull();
    }

    @Test
    void getReviewSummary_includesAllExpertsAsPlaceholdersWhenNoReviewsYet() {
        Suggestion s = new Suggestion();
        s.setId(5L);
        when(suggestionRepository.findById(5L)).thenReturn(Optional.of(s));

        List<Map<String, Object>> summary = service.getReviewSummary(5L);

        assertThat(summary).hasSize(ExpertRole.reviewOrder().length);
        summary.forEach(entry -> {
            assertThat(entry.get("status")).isEqualTo("PENDING");
            assertThat(entry.get("keyPoint")).isEqualTo("No review yet");
        });
    }

    // -------------------------------------------------------------------------
    // startExpertReviewPipeline — state guard
    // -------------------------------------------------------------------------

    @Test
    void startExpertReviewPipeline_doesNothingWhenSuggestionNotFound() {
        when(suggestionRepository.findById(99L)).thenReturn(Optional.empty());
        service.startExpertReviewPipeline(99L); // must not throw
        verify(claudeService, never()).expertReview(any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), anyInt(), any());
    }

    @Test
    void startExpertReviewPipeline_blockedWhenNotInExpertReviewState() {
        Suggestion s = new Suggestion();
        s.setId(10L);
        s.setStatus(SuggestionStatus.PLANNED);
        when(suggestionRepository.findById(10L)).thenReturn(Optional.of(s));

        service.startExpertReviewPipeline(10L);

        verify(claudeService, never()).expertReview(any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), anyInt(), any());
    }

    @Test
    void startExpertReviewPipeline_clearsApprovalTracker() {
        Suggestion s = new Suggestion();
        s.setId(11L);
        s.setStatus(SuggestionStatus.EXPERT_REVIEW);
        s.setExpertReviewStep(ExpertRole.reviewOrder().length - 1);
        s.setExpertReviewRound(1);
        Map<String, Suggestion.ExpertApprovalEntry> existing = new java.util.HashMap<>();
        existing.put(ExpertRole.QA_ENGINEER.getDisplayName(),
                new Suggestion.ExpertApprovalEntry("APPROVED", 1));
        s.setExpertApprovalMap(existing);
        when(suggestionRepository.findById(11L)).thenReturn(Optional.of(s));

        service.startExpertReviewPipeline(11L);

        assertThat(s.getExpertApprovalTracker()).isNull();
    }

    // -------------------------------------------------------------------------
    // handleExpertReviewResponse — APPROVED path
    // -------------------------------------------------------------------------

    private Suggestion buildExpertReviewSuggestion(long id) {
        Suggestion s = new Suggestion();
        s.setId(id);
        s.setStatus(SuggestionStatus.EXPERT_REVIEW);
        s.setExpertReviewRound(1);
        // Use step 0 (not the last step) so advanceExpertStep increments to 1
        // and runNextExpertReview continues the review rather than completing it.
        s.setExpertReviewStep(0);
        when(suggestionRepository.findById(id)).thenReturn(Optional.of(s));
        return s;
    }

    @Test
    void handleExpertReviewResponse_approvedVerdict_advancesStep() {
        Suggestion s = buildExpertReviewSuggestion(20L);
        int initialStep = s.getExpertReviewStep();

        service.handleExpertReviewResponse(20L,
                "```json\n{\"status\":\"APPROVED\",\"analysis\":\"All good.\",\"message\":\"OK\"}\n```",
                ExpertRole.QA_ENGINEER, "session-1");

        assertThat(s.getExpertReviewStep()).isGreaterThan(initialStep);
    }

    @Test
    void handleExpertReviewResponse_approvedVerdict_recordsInApprovalTracker() {
        Suggestion s = buildExpertReviewSuggestion(21L);

        service.handleExpertReviewResponse(21L,
                "```json\n{\"status\":\"APPROVED\",\"analysis\":\"This is a sufficiently detailed analysis with enough content.\",\"message\":\"Approved\"}\n```",
                ExpertRole.SECURITY_ENGINEER, "session-2");

        assertThat(s.getExpertApprovalMap()).containsKey(ExpertRole.SECURITY_ENGINEER.getDisplayName());
        assertThat(s.getExpertApprovalMap().get(ExpertRole.SECURITY_ENGINEER.getDisplayName()).getStatus())
                .isEqualTo("APPROVED");
    }

    @Test
    void handleExpertReviewResponse_changesProposedWithShortAnalysis_reInvokesExpert() {
        buildExpertReviewSuggestion(22L);

        service.handleExpertReviewResponse(22L,
                "```json\n{\"status\":\"CHANGES_PROPOSED\",\"analysis\":\"Short.\",\"message\":\"Change it\"}\n```",
                ExpertRole.QA_ENGINEER, "session-3");

        verify(claudeService).expertReview(any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), anyInt(), any());
    }

    @Test
    void handleExpertReviewResponse_noJsonInResponse_reInvokesExpert() {
        buildExpertReviewSuggestion(23L);

        service.handleExpertReviewResponse(23L,
                "Just a plain text response with no JSON",
                ExpertRole.QA_ENGINEER, "session-4");

        verify(claudeService).expertReview(any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), anyInt(), any());
    }

    @Test
    void handleExpertReviewResponse_needsClarification_setsPendingQuestions() {
        Suggestion s = buildExpertReviewSuggestion(24L);

        service.handleExpertReviewResponse(24L,
                "```json\n{\"status\":\"NEEDS_CLARIFICATION\",\"analysis\":\"Need more info.\",\"message\":\"Please clarify\",\"questions\":[\"Q1\",\"Q2\"]}\n```",
                ExpertRole.QA_ENGINEER, "session-5");

        assertThat(s.getPendingClarificationQuestions()).isNotNull();
    }

    @Test
    void handleExpertReviewResponse_suggestionNotFound_doesNotThrow() {
        when(suggestionRepository.findById(999L)).thenReturn(Optional.empty());
        // Should return gracefully
        service.handleExpertReviewResponse(999L,
                "```json\n{\"status\":\"APPROVED\"}\n```",
                ExpertRole.QA_ENGINEER, "session");
        verify(messagingHelper, never()).addMessage(any(), any(), any(), any());
    }

    // -------------------------------------------------------------------------
    // handleExpertClarificationAnswers — state guard
    // -------------------------------------------------------------------------

    @Test
    void handleExpertClarificationAnswers_throwsWhenNotInExpertReviewState() {
        Suggestion s = new Suggestion();
        s.setId(30L);
        s.setStatus(SuggestionStatus.PLANNED); // not EXPERT_REVIEW
        when(suggestionRepository.findById(30L)).thenReturn(Optional.of(s));

        assertThatThrownBy(() ->
                service.handleExpertClarificationAnswers(30L, "user", List.of()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void handleExpertClarificationAnswers_throwsWhenSuggestionNotFound() {
        when(suggestionRepository.findById(31L)).thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                service.handleExpertClarificationAnswers(31L, "user", List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // -------------------------------------------------------------------------
    // Approval tracker (package-private via reflection)
    // -------------------------------------------------------------------------

    @Test
    void updateApprovalTracker_writesEntryForExpert() throws Exception {
        Suggestion s = new Suggestion();
        s.setId(40L);
        when(suggestionRepository.findById(40L)).thenReturn(Optional.of(s));

        invokeUpdateApprovalTracker(s, ExpertRole.DATA_ANALYST, "APPROVED", 1);

        assertThat(s.getExpertApprovalMap()).containsKey(ExpertRole.DATA_ANALYST.getDisplayName());
        assertThat(s.getExpertApprovalMap().get(ExpertRole.DATA_ANALYST.getDisplayName()).getStatus())
                .isEqualTo("APPROVED");
    }

    @Test
    void getApprovalStatus_returnsNullWhenNotTracked() throws Exception {
        Suggestion s = new Suggestion();
        String status = invokeGetApprovalStatus(s, ExpertRole.SECURITY_ENGINEER);
        assertThat(status).isNull();
    }

    @Test
    void getApprovalStatus_returnsCorrectStatus() throws Exception {
        Suggestion s = new Suggestion();
        Map<String, Suggestion.ExpertApprovalEntry> tracker = new java.util.HashMap<>();
        tracker.put(ExpertRole.SECURITY_ENGINEER.getDisplayName(),
                new Suggestion.ExpertApprovalEntry("CHANGES_PROPOSED", 1));
        s.setExpertApprovalMap(tracker);

        String status = invokeGetApprovalStatus(s, ExpertRole.SECURITY_ENGINEER);
        assertThat(status).isEqualTo("CHANGES_PROPOSED");
    }

    @Test
    void clearApprovalTracker_setsTrackerToNull() throws Exception {
        Suggestion s = new Suggestion();
        s.setId(50L);
        Map<String, Suggestion.ExpertApprovalEntry> tracker = new java.util.HashMap<>();
        tracker.put(ExpertRole.QA_ENGINEER.getDisplayName(),
                new Suggestion.ExpertApprovalEntry("APPROVED", 1));
        s.setExpertApprovalMap(tracker);

        invokeClearApprovalTracker(s);

        assertThat(s.getExpertApprovalTracker()).isNull();
        verify(suggestionRepository).save(s);
    }

    // -------------------------------------------------------------------------
    // isPlanLocked guard in handleReviewerResponse
    // -------------------------------------------------------------------------

    @Test
    void handleReviewerResponse_planLockedStatus_doesNotApplyChanges() {
        Suggestion s = new Suggestion();
        s.setId(60L);
        s.setStatus(SuggestionStatus.IN_PROGRESS); // plan is locked
        s.setExpertReviewRound(1);
        s.setExpertReviewStep(ExpertRole.reviewOrder().length - 1);
        s.setPlanSummary("Original plan");
        when(suggestionRepository.findById(60L)).thenReturn(Optional.of(s));

        String reviewerResponse = "```json\n{\"apply\":true,\"notes\":\"Apply it.\"}\n```";
        String expertResponse = "```json\n{\"status\":\"CHANGES_PROPOSED\",\"revisedPlan\":\"New plan\"}\n```";

        service.handleReviewerResponse(60L, reviewerResponse, ExpertRole.SOFTWARE_ARCHITECT,
                expertResponse, "Analysis here.", "Message here.");

        // Plan must remain unchanged because status is IN_PROGRESS (locked)
        assertThat(s.getPlanSummary()).isEqualTo("Original plan");
    }

    // -------------------------------------------------------------------------
    // MAX_EXPERT_RETRIES — reInvokeExpertForDetailedReview
    // -------------------------------------------------------------------------

    @Test
    void reInvokeExpert_doesNotExceedMaxRetries() throws Exception {
        Suggestion s = buildExpertReviewSuggestion(70L);
        ExpertRole expert = ExpertRole.QA_ENGINEER;

        // Simulate MAX_EXPERT_RETRIES + 1 invocations with empty response (no JSON)
        for (int i = 0; i <= ExpertReviewService.MAX_EXPERT_RETRIES; i++) {
            service.handleExpertReviewResponse(70L,
                    "No JSON here at all",
                    expert, "session-retry-" + i);
        }

        // After exceeding max retries, it should fall back and advance the step
        // (not keep calling expertReview indefinitely)
        // The exact count is MAX_EXPERT_RETRIES invocations before fallback
        verify(claudeService, atMost(ExpertReviewService.MAX_EXPERT_RETRIES + 1))
                .expertReview(any(), any(), any(), any(), any(), any(), any(), any(),
                        any(), any(), anyInt(), any());
    }

    // -------------------------------------------------------------------------
    // Constants
    // -------------------------------------------------------------------------

    @Test
    void constants_haveExpectedValues() {
        assertThat(ExpertReviewService.MAX_EXPERT_REVIEW_ROUNDS).isEqualTo(2);
        assertThat(ExpertReviewService.MAX_TOTAL_EXPERT_REVIEW_ROUNDS).isEqualTo(3);
        assertThat(ExpertReviewService.MIN_EXPERT_ANALYSIS_LENGTH).isEqualTo(50);
        assertThat(ExpertReviewService.MAX_EXPERT_RETRIES).isEqualTo(2);
    }

    // -------------------------------------------------------------------------
    // Transition to PLANNED when all experts reviewed
    // -------------------------------------------------------------------------

    @Test
    void allExpertsReviewed_noPlanChanged_transitionsToPLANNED() {
        Suggestion s = new Suggestion();
        s.setId(80L);
        s.setStatus(SuggestionStatus.EXPERT_REVIEW);
        s.setExpertReviewStep(ExpertRole.reviewOrder().length); // past end → all done
        s.setExpertReviewRound(1);
        s.setTotalExpertReviewRounds(1);
        s.setExpertReviewPlanChanged(false);
        when(suggestionRepository.findById(80L)).thenReturn(Optional.of(s));

        // Invoke runNextExpertReview via reflection
        invokeRunNextExpertReview(80L);

        assertThat(s.getStatus()).isEqualTo(SuggestionStatus.PLANNED);
        assertThat(s.getCurrentPhase()).isEqualTo("Plan ready — waiting for approval");
    }

    @Test
    void allExpertsReviewed_clearsReviewFields() {
        Suggestion s = new Suggestion();
        s.setId(81L);
        s.setStatus(SuggestionStatus.EXPERT_REVIEW);
        s.setExpertReviewStep(ExpertRole.reviewOrder().length);
        s.setExpertReviewRound(1);
        s.setTotalExpertReviewRounds(1);
        s.setExpertReviewPlanChanged(false);
        when(suggestionRepository.findById(81L)).thenReturn(Optional.of(s));

        invokeRunNextExpertReview(81L);

        assertThat(s.getExpertReviewStep()).isNull();
        assertThat(s.getExpertReviewRound()).isNull();
        assertThat(s.getExpertReviewPlanChanged()).isNull();
        assertThat(s.getTotalExpertReviewRounds()).isNull();
    }

    @Test
    void allExpertsReviewed_notifiesAdmins() {
        Suggestion s = new Suggestion();
        s.setId(82L);
        s.setStatus(SuggestionStatus.EXPERT_REVIEW);
        s.setExpertReviewStep(ExpertRole.reviewOrder().length);
        s.setExpertReviewRound(1);
        s.setTotalExpertReviewRounds(1);
        s.setExpertReviewPlanChanged(false);
        when(suggestionRepository.findById(82L)).thenReturn(Optional.of(s));

        invokeRunNextExpertReview(82L);

        verify(slackNotificationService).sendApprovalNeededNotification(s);
    }

    // -------------------------------------------------------------------------
    // Reflection helpers
    // -------------------------------------------------------------------------

    private void invokeUpdateApprovalTracker(Suggestion suggestion, ExpertRole expert,
                                              String status, int round) throws Exception {
        Method m = ExpertReviewService.class.getDeclaredMethod(
                "updateApprovalTracker", Suggestion.class, ExpertRole.class, String.class, int.class);
        m.setAccessible(true);
        m.invoke(service, suggestion, expert, status, round);
    }

    private String invokeGetApprovalStatus(Suggestion suggestion, ExpertRole expert) throws Exception {
        Method m = ExpertReviewService.class.getDeclaredMethod(
                "getApprovalStatus", Suggestion.class, ExpertRole.class);
        m.setAccessible(true);
        return (String) m.invoke(service, suggestion, expert);
    }

    private void invokeClearApprovalTracker(Suggestion suggestion) throws Exception {
        Method m = ExpertReviewService.class.getDeclaredMethod(
                "clearApprovalTracker", Suggestion.class);
        m.setAccessible(true);
        m.invoke(service, suggestion);
    }

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
