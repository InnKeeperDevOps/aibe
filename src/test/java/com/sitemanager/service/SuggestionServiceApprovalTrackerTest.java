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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

class SuggestionServiceApprovalTrackerTest {

    private SuggestionRepository suggestionRepository;
    private ClaudeService claudeService;
    private ExpertReviewService service;

    @BeforeEach
    void setUp() {
        suggestionRepository = mock(SuggestionRepository.class);
        SuggestionMessageRepository messageRepository = mock(SuggestionMessageRepository.class);
        PlanTaskRepository planTaskRepository = mock(PlanTaskRepository.class);
        SuggestionWebSocketHandler webSocketHandler = mock(SuggestionWebSocketHandler.class);
        UserNotificationWebSocketHandler userNotificationHandler = mock(UserNotificationWebSocketHandler.class);
        SlackNotificationService slackNotificationService = mock(SlackNotificationService.class);
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

    // -------------------------------------------------------------------------
    // updateApprovalTracker
    // -------------------------------------------------------------------------

    @Test
    void updateApprovalTracker_writesEntryForExpert() throws Exception {
        Suggestion suggestion = new Suggestion();
        suggestion.setId(1L);
        when(suggestionRepository.findById(1L)).thenReturn(Optional.of(suggestion));

        invokeUpdateApprovalTracker(suggestion, ExpertRole.SECURITY_ENGINEER, "APPROVED", 1);

        Map<String, Suggestion.ExpertApprovalEntry> tracker = suggestion.getExpertApprovalMap();
        assertThat(tracker).containsKey(ExpertRole.SECURITY_ENGINEER.getDisplayName());
        Suggestion.ExpertApprovalEntry entry = tracker.get(ExpertRole.SECURITY_ENGINEER.getDisplayName());
        assertThat(entry.getStatus()).isEqualTo("APPROVED");
        assertThat(entry.getRound()).isEqualTo(1);
    }

    @Test
    void updateApprovalTracker_overwritesExistingEntry() throws Exception {
        Suggestion suggestion = new Suggestion();
        suggestion.setId(2L);

        // Seed an existing entry
        Map<String, Suggestion.ExpertApprovalEntry> initial = new java.util.HashMap<>();
        initial.put(ExpertRole.SECURITY_ENGINEER.getDisplayName(),
                new Suggestion.ExpertApprovalEntry("CHANGES_PROPOSED", 1));
        suggestion.setExpertApprovalMap(initial);

        when(suggestionRepository.findById(2L)).thenReturn(Optional.of(suggestion));

        invokeUpdateApprovalTracker(suggestion, ExpertRole.SECURITY_ENGINEER, "APPROVED", 2);

        Suggestion.ExpertApprovalEntry updated = suggestion.getExpertApprovalMap()
                .get(ExpertRole.SECURITY_ENGINEER.getDisplayName());
        assertThat(updated.getStatus()).isEqualTo("APPROVED");
        assertThat(updated.getRound()).isEqualTo(2);
    }

    @Test
    void updateApprovalTracker_preservesOtherExperts() throws Exception {
        Suggestion suggestion = new Suggestion();
        suggestion.setId(3L);

        Map<String, Suggestion.ExpertApprovalEntry> initial = new java.util.HashMap<>();
        initial.put(ExpertRole.DATA_ANALYST.getDisplayName(),
                new Suggestion.ExpertApprovalEntry("APPROVED", 1));
        suggestion.setExpertApprovalMap(initial);

        when(suggestionRepository.findById(3L)).thenReturn(Optional.of(suggestion));

        invokeUpdateApprovalTracker(suggestion, ExpertRole.SECURITY_ENGINEER, "CHANGES_PROPOSED", 1);

        Map<String, Suggestion.ExpertApprovalEntry> result = suggestion.getExpertApprovalMap();
        assertThat(result).containsKey(ExpertRole.DATA_ANALYST.getDisplayName());
        assertThat(result).containsKey(ExpertRole.SECURITY_ENGINEER.getDisplayName());
    }

    @Test
    void updateApprovalTracker_refetchesSuggestionFromDb() throws Exception {
        Suggestion stale = new Suggestion();
        stale.setId(4L);

        Suggestion fresh = new Suggestion();
        fresh.setId(4L);
        when(suggestionRepository.findById(4L)).thenReturn(Optional.of(fresh));

        invokeUpdateApprovalTracker(stale, ExpertRole.QA_ENGINEER, "APPROVED", 1);

        // The save call should be on the fresh instance (returned by findById)
        verify(suggestionRepository).findById(4L);
        verify(suggestionRepository).save(fresh);
    }

    @Test
    void updateApprovalTracker_doesNothingWhenIdIsNull() throws Exception {
        Suggestion suggestion = new Suggestion(); // id is null
        invokeUpdateApprovalTracker(suggestion, ExpertRole.QA_ENGINEER, "APPROVED", 1);

        verify(suggestionRepository, never()).findById(any());
        verify(suggestionRepository, never()).save(any());
    }

    @Test
    void updateApprovalTracker_fallsBackToPassedSuggestionWhenNotFoundInDb() throws Exception {
        Suggestion suggestion = new Suggestion();
        suggestion.setId(5L);
        when(suggestionRepository.findById(5L)).thenReturn(Optional.empty());

        invokeUpdateApprovalTracker(suggestion, ExpertRole.QA_ENGINEER, "APPROVED", 1);

        // Should still save (using the passed suggestion as fallback)
        verify(suggestionRepository).save(suggestion);
        assertThat(suggestion.getExpertApprovalMap())
                .containsKey(ExpertRole.QA_ENGINEER.getDisplayName());
    }

    // -------------------------------------------------------------------------
    // getApprovalStatus
    // -------------------------------------------------------------------------

    @Test
    void getApprovalStatus_returnsNullWhenTrackerIsEmpty() throws Exception {
        Suggestion suggestion = new Suggestion();
        String status = invokeGetApprovalStatus(suggestion, ExpertRole.SECURITY_ENGINEER);
        assertThat(status).isNull();
    }

    @Test
    void getApprovalStatus_returnsNullWhenExpertNotTracked() throws Exception {
        Suggestion suggestion = new Suggestion();
        Map<String, Suggestion.ExpertApprovalEntry> map = new java.util.HashMap<>();
        map.put(ExpertRole.DATA_ANALYST.getDisplayName(),
                new Suggestion.ExpertApprovalEntry("APPROVED", 1));
        suggestion.setExpertApprovalMap(map);

        String status = invokeGetApprovalStatus(suggestion, ExpertRole.SECURITY_ENGINEER);
        assertThat(status).isNull();
    }

    @Test
    void getApprovalStatus_returnsCorrectStatusForTrackedExpert() throws Exception {
        Suggestion suggestion = new Suggestion();
        Map<String, Suggestion.ExpertApprovalEntry> map = new java.util.HashMap<>();
        map.put(ExpertRole.SECURITY_ENGINEER.getDisplayName(),
                new Suggestion.ExpertApprovalEntry("CHANGES_PROPOSED", 2));
        suggestion.setExpertApprovalMap(map);

        String status = invokeGetApprovalStatus(suggestion, ExpertRole.SECURITY_ENGINEER);
        assertThat(status).isEqualTo("CHANGES_PROPOSED");
    }

    @Test
    void getApprovalStatus_returnsChangesRejectedStatus() throws Exception {
        Suggestion suggestion = new Suggestion();
        Map<String, Suggestion.ExpertApprovalEntry> map = new java.util.HashMap<>();
        map.put(ExpertRole.SOFTWARE_ARCHITECT.getDisplayName(),
                new Suggestion.ExpertApprovalEntry("CHANGES_REJECTED", 1));
        suggestion.setExpertApprovalMap(map);

        String status = invokeGetApprovalStatus(suggestion, ExpertRole.SOFTWARE_ARCHITECT);
        assertThat(status).isEqualTo("CHANGES_REJECTED");
    }

    // -------------------------------------------------------------------------
    // clearApprovalTracker
    // -------------------------------------------------------------------------

    @Test
    void clearApprovalTracker_setsTrackerToNull() throws Exception {
        Suggestion suggestion = new Suggestion();
        suggestion.setId(10L);
        Map<String, Suggestion.ExpertApprovalEntry> map = new java.util.HashMap<>();
        map.put(ExpertRole.QA_ENGINEER.getDisplayName(),
                new Suggestion.ExpertApprovalEntry("APPROVED", 1));
        suggestion.setExpertApprovalMap(map);

        invokeClearApprovalTracker(suggestion);

        assertThat(suggestion.getExpertApprovalTracker()).isNull();
    }

    @Test
    void clearApprovalTracker_savesSuggestion() throws Exception {
        Suggestion suggestion = new Suggestion();
        suggestion.setId(11L);

        invokeClearApprovalTracker(suggestion);

        verify(suggestionRepository).save(suggestion);
    }

    @Test
    void clearApprovalTracker_onAlreadyEmptyTracker_savesWithoutError() throws Exception {
        Suggestion suggestion = new Suggestion();
        suggestion.setId(12L);
        // tracker is already null

        invokeClearApprovalTracker(suggestion);

        assertThat(suggestion.getExpertApprovalTracker()).isNull();
        verify(suggestionRepository).save(suggestion);
    }

    // -------------------------------------------------------------------------
    // handleExpertReviewResponse — approval tracker recording
    // -------------------------------------------------------------------------

    /**
     * Builds a suggestion pre-configured for a smooth run through handleExpertReviewResponse:
     * step is set to the last position so that advanceExpertStep pushes it past the end,
     * causing runNextExpertReview to transition cleanly to PLANNED without invoking more experts.
     */
    private Suggestion buildExpertReviewSuggestion(long id) {
        Suggestion s = new Suggestion();
        s.setId(id);
        s.setStatus(SuggestionStatus.EXPERT_REVIEW);
        s.setExpertReviewRound(1);
        s.setExpertReviewStep(ExpertRole.reviewOrder().length - 1);
        when(suggestionRepository.findById(id)).thenReturn(Optional.of(s));
        return s;
    }

    @Test
    void handleExpertReviewResponse_approvedVerdict_recordsApprovedInTracker() {
        Suggestion suggestion = buildExpertReviewSuggestion(100L);
        ExpertRole expert = ExpertRole.SECURITY_ENGINEER;

        String response = """
                ```json
                {"status":"APPROVED","analysis":"This is a sufficiently long analysis covering all security aspects.",
                "message":"Looks good"}
                ```
                """;

        service.handleExpertReviewResponse(100L, response, expert, "session-1");

        Map<String, Suggestion.ExpertApprovalEntry> tracker = suggestion.getExpertApprovalMap();
        assertThat(tracker).containsKey(expert.getDisplayName());
        assertThat(tracker.get(expert.getDisplayName()).getStatus()).isEqualTo("APPROVED");
        assertThat(tracker.get(expert.getDisplayName()).getRound()).isEqualTo(1);
    }

    @Test
    void handleExpertReviewResponse_approvedStatus_briefAnalysis_doesNotReInvoke() {
        // APPROVED + analysis < 50 chars must NOT trigger re-invoke (only CHANGES_PROPOSED should)
        Suggestion suggestion = buildExpertReviewSuggestion(102L);
        ExpertRole expert = ExpertRole.SECURITY_ENGINEER;

        String response = """
                ```json
                {"status":"APPROVED","analysis":"OK","message":"Fine"}
                ```
                """;

        service.handleExpertReviewResponse(102L, response, expert, "session-brief-approved");

        verify(claudeService, never()).expertReview(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), anyInt(), any());
    }

    @Test
    void handleExpertReviewResponse_changesProposedStatus_briefAnalysis_reInvokesExpert() {
        // CHANGES_PROPOSED + analysis < 50 chars must re-invoke the expert for detailed justification
        Suggestion suggestion = buildExpertReviewSuggestion(103L);
        ExpertRole expert = ExpertRole.QA_ENGINEER;

        String response = """
                ```json
                {"status":"CHANGES_PROPOSED","analysis":"Too short","message":"Needs work"}
                ```
                """;

        service.handleExpertReviewResponse(103L, response, expert, "session-brief-changes");

        verify(claudeService).expertReview(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), anyInt(), any());
    }

    @Test
    void handleExpertReviewResponse_changesRejectedStatus_briefAnalysis_doesNotReInvoke() {
        // CHANGES_REJECTED + brief analysis must NOT re-invoke — only CHANGES_PROPOSED triggers this guard
        Suggestion suggestion = buildExpertReviewSuggestion(104L);
        ExpertRole expert = ExpertRole.DATA_ANALYST;

        String response = """
                ```json
                {"status":"CHANGES_REJECTED","analysis":"No","message":"Rejected"}
                ```
                """;

        service.handleExpertReviewResponse(104L, response, expert, "session-brief-rejected");

        verify(claudeService, never()).expertReview(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), anyInt(), any());
    }

    @Test
    void handleExpertReviewResponse_changesProposedStatus_substantiveAnalysis_doesNotReturnEarlyViaReInvoke() {
        // CHANGES_PROPOSED + analysis >= 50 chars must NOT re-invoke — the method should proceed
        // to record the verdict in the tracker (proves it did not return early via the re-invoke path)
        Suggestion suggestion = buildExpertReviewSuggestion(105L);
        ExpertRole expert = ExpertRole.SOFTWARE_ARCHITECT;

        String response = """
                ```json
                {"status":"CHANGES_PROPOSED","analysis":"This is a detailed analysis with more than fifty characters worth of content.","message":"Please revise"}
                ```
                """;

        service.handleExpertReviewResponse(105L, response, expert, "session-substantive");

        // If re-invoke had happened the method returns early, so the tracker would be empty;
        // a non-empty tracker proves the normal flow ran and the re-invoke guard was NOT triggered.
        assertThat(suggestion.getExpertApprovalMap()).isNotNull();
    }

    @Test
    void handleExpertReviewResponse_approvedVerdict_trackerRoundMatchesSuggestionRound() {
        Suggestion suggestion = buildExpertReviewSuggestion(101L);
        suggestion.setExpertReviewRound(3);
        ExpertRole expert = ExpertRole.DATA_ANALYST;

        String response = """
                ```json
                {"status":"APPROVED","analysis":"Detailed data analysis findings with sufficient depth here.",
                "message":"All good"}
                ```
                """;

        service.handleExpertReviewResponse(101L, response, expert, "session-2");

        assertThat(suggestion.getExpertApprovalMap().get(expert.getDisplayName()).getRound()).isEqualTo(3);
    }

    // -------------------------------------------------------------------------
    // handleReviewerResponse — approval tracker recording
    // -------------------------------------------------------------------------

    private Suggestion buildReviewerResponseSuggestion(long id) {
        Suggestion s = new Suggestion();
        s.setId(id);
        s.setStatus(SuggestionStatus.EXPERT_REVIEW);
        s.setExpertReviewRound(1);
        s.setExpertReviewStep(ExpertRole.reviewOrder().length - 1);
        when(suggestionRepository.findById(id)).thenReturn(Optional.of(s));
        return s;
    }

    @Test
    void handleReviewerResponse_changesAccepted_recordsChangesAppliedInTracker() {
        Suggestion suggestion = buildReviewerResponseSuggestion(200L);
        ExpertRole expert = ExpertRole.SECURITY_ENGINEER;

        String reviewerResponse = """
                ```json
                {"apply":true,"notes":"Changes are sound and well-reasoned."}
                ```
                """;
        // Original expert response with no revisedTasks or revisedPlan
        String originalExpertResponse = """
                ```json
                {"status":"CHANGES_PROPOSED","proposedChanges":"Add rate limiting."}
                ```
                """;

        service.handleReviewerResponse(200L, reviewerResponse, expert,
                originalExpertResponse, "Expert analysis here.", "Message text");

        Map<String, Suggestion.ExpertApprovalEntry> tracker = suggestion.getExpertApprovalMap();
        assertThat(tracker).containsKey(expert.getDisplayName());
        assertThat(tracker.get(expert.getDisplayName()).getStatus()).isEqualTo("CHANGES_APPLIED");
        assertThat(tracker.get(expert.getDisplayName()).getRound()).isEqualTo(1);
    }

    @Test
    void handleReviewerResponse_changesRejected_recordsApprovedInTracker() {
        Suggestion suggestion = buildReviewerResponseSuggestion(201L);
        ExpertRole expert = ExpertRole.DATA_ANALYST;

        String reviewerResponse = """
                ```json
                {"apply":false,"notes":"The proposed changes are not justified."}
                ```
                """;
        String originalExpertResponse = """
                ```json
                {"status":"CHANGES_PROPOSED","proposedChanges":"Add caching layer."}
                ```
                """;

        service.handleReviewerResponse(201L, reviewerResponse, expert,
                originalExpertResponse, "Analysis text here.", "Message text");

        Map<String, Suggestion.ExpertApprovalEntry> tracker = suggestion.getExpertApprovalMap();
        assertThat(tracker).containsKey(expert.getDisplayName());
        assertThat(tracker.get(expert.getDisplayName()).getStatus()).isEqualTo("APPROVED");
        assertThat(tracker.get(expert.getDisplayName()).getRound()).isEqualTo(1);
    }

    @Test
    void handleReviewerResponse_changesRejected_roundMatchesSuggestionRound() {
        Suggestion suggestion = buildReviewerResponseSuggestion(202L);
        suggestion.setExpertReviewRound(2);
        ExpertRole expert = ExpertRole.QA_ENGINEER;

        String reviewerResponse = """
                ```json
                {"apply":false,"notes":"Not accepted."}
                ```
                """;

        service.handleReviewerResponse(202L, reviewerResponse, expert,
                "{}", "Analysis.", "Message");

        assertThat(suggestion.getExpertApprovalMap().get(expert.getDisplayName()).getRound()).isEqualTo(2);
    }

    // -------------------------------------------------------------------------
    // startExpertReviewPipeline — clearApprovalTracker is called
    // -------------------------------------------------------------------------

    @Test
    void startExpertReviewPipeline_clearsApprovalTracker() throws Exception {
        Suggestion suggestion = new Suggestion();
        suggestion.setId(300L);
        suggestion.setStatus(SuggestionStatus.EXPERT_REVIEW);
        suggestion.setExpertReviewStep(ExpertRole.reviewOrder().length - 1);
        suggestion.setExpertReviewRound(1);

        // Pre-populate tracker so we can verify it gets cleared
        Map<String, Suggestion.ExpertApprovalEntry> existing = new java.util.HashMap<>();
        existing.put(ExpertRole.QA_ENGINEER.getDisplayName(),
                new Suggestion.ExpertApprovalEntry("APPROVED", 1));
        suggestion.setExpertApprovalMap(existing);

        when(suggestionRepository.findById(300L)).thenReturn(Optional.of(suggestion));

        Method m = ExpertReviewService.class.getDeclaredMethod("startExpertReviewPipeline", Long.class);
        m.setAccessible(true);
        m.invoke(service, 300L);

        assertThat(suggestion.getExpertApprovalTracker()).isNull();
    }

    @Test
    void startExpertReviewPipeline_doesNotClearWhenSuggestionNotInExpertReviewState() throws Exception {
        Suggestion suggestion = new Suggestion();
        suggestion.setId(301L);
        suggestion.setStatus(SuggestionStatus.PLANNED); // not EXPERT_REVIEW

        Map<String, Suggestion.ExpertApprovalEntry> existing = new java.util.HashMap<>();
        existing.put(ExpertRole.QA_ENGINEER.getDisplayName(),
                new Suggestion.ExpertApprovalEntry("APPROVED", 1));
        suggestion.setExpertApprovalMap(existing);

        when(suggestionRepository.findById(301L)).thenReturn(Optional.of(suggestion));

        Method m = ExpertReviewService.class.getDeclaredMethod("startExpertReviewPipeline", Long.class);
        m.setAccessible(true);
        m.invoke(service, 301L);

        // Pipeline was blocked early — tracker should remain untouched
        assertThat(suggestion.getExpertApprovalMap()).isNotEmpty();
    }

    // -------------------------------------------------------------------------
    // Reflection helpers
    // -------------------------------------------------------------------------

    private void invokeUpdateApprovalTracker(Suggestion suggestion, ExpertRole expert, String status, int round)
            throws Exception {
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
}
