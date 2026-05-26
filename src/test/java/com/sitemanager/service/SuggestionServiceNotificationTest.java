package com.sitemanager.service;

import com.sitemanager.model.Suggestion;
import com.sitemanager.model.SuggestionMessage;
import com.sitemanager.model.SiteSettings;
import com.sitemanager.model.User;
import com.sitemanager.model.enums.SenderType;
import com.sitemanager.model.enums.SuggestionStatus;
import com.sitemanager.model.enums.UserRole;
import com.sitemanager.repository.ExpertReviewCostRepository;
import com.sitemanager.repository.PlanTaskRepository;
import com.sitemanager.repository.SuggestionMessageRepository;
import com.sitemanager.repository.SuggestionRepository;
import com.sitemanager.repository.UserRepository;
import com.sitemanager.websocket.SuggestionWebSocketHandler;
import com.sitemanager.websocket.UserNotificationWebSocketHandler;
import com.sitemanager.service.ExpertReviewService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class SuggestionServiceNotificationTest {

    private SuggestionRepository suggestionRepository;
    private SuggestionMessageRepository messageRepository;
    private SuggestionWebSocketHandler webSocketHandler;
    private UserNotificationWebSocketHandler userNotificationHandler;
    private SiteSettingsService siteSettingsService;
    private SlackNotificationService slackNotificationService;
    private UserRepository userRepository;
    private SuggestionService service;
    private ExpertReviewService expertReviewService;
    private PlanExecutionService planExecutionService;
    private SuggestionMessagingHelper messagingHelper;

    @BeforeEach
    void setUp() {
        suggestionRepository = mock(SuggestionRepository.class);
        messageRepository = mock(SuggestionMessageRepository.class);
        webSocketHandler = mock(SuggestionWebSocketHandler.class);
        userNotificationHandler = mock(UserNotificationWebSocketHandler.class);
        siteSettingsService = mock(SiteSettingsService.class);
        slackNotificationService = mock(SlackNotificationService.class);
        userRepository = mock(UserRepository.class);

        // Return a completed future so async calls don't hang
        when(slackNotificationService.sendNotification(any(), any()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(slackNotificationService.sendApprovalNeededNotification(any()))
                .thenReturn(CompletableFuture.completedFuture(null));

        // Return a real SiteSettings so getSettings().getXxx() doesn't NPE
        when(siteSettingsService.getSettings()).thenReturn(new SiteSettings());

        // Return empty admin lists by default
        when(userRepository.findByRole(any())).thenReturn(List.of());

        // Return a non-null message so addMessage's msg.getId() doesn't NPE
        SuggestionMessage savedMsg = mock(SuggestionMessage.class);
        when(savedMsg.getId()).thenReturn(0L);
        when(messageRepository.save(any())).thenReturn(savedMsg);

        messagingHelper = new SuggestionMessagingHelper(
                suggestionRepository,
                messageRepository,
                mock(PlanTaskRepository.class),
                webSocketHandler,
                userNotificationHandler,
                slackNotificationService,
                siteSettingsService
        );

        SpendingLimitService spendingLimitService = mock(SpendingLimitService.class);
        when(spendingLimitService.checkCanStartReview(any()))
                .thenReturn(SpendingLimitService.LimitCheck.allowed());

        expertReviewService = new ExpertReviewService(
                suggestionRepository,
                messageRepository,
                mock(PlanTaskRepository.class),
                mock(ClaudeService.class),
                messagingHelper,
                webSocketHandler,
                userNotificationHandler,
                slackNotificationService,
                userRepository,
                mock(ExpertReviewCostRepository.class),
                mock(CostRollupService.class),
                spendingLimitService,
                mock(SpendingAlertService.class),
                mock(PlanExecutionService.class)
        );

        planExecutionService = new PlanExecutionService(
                suggestionRepository,
                mock(PlanTaskRepository.class),
                mock(ClaudeService.class),
                messagingHelper,
                siteSettingsService,
                webSocketHandler,
                slackNotificationService
        );

        service = new SuggestionService(
                suggestionRepository,
                messageRepository,
                mock(PlanTaskRepository.class),
                mock(ClaudeService.class),
                siteSettingsService,
                slackNotificationService,
                messagingHelper,
                expertReviewService,
                planExecutionService
        );
    }

    // --- Existing broadcastClarificationQuestions tests ---

    @Test
    void broadcastClarificationQuestions_sendsUserNotificationToAuthor() throws Exception {
        Suggestion suggestion = new Suggestion();
        suggestion.setTitle("Add dark mode");
        suggestion.setAuthorName("alice");

        when(suggestionRepository.findById(42L)).thenReturn(Optional.of(suggestion));

        invokeBroadcastClarificationQuestions(42L, List.of("Q1?", "Q2?"));

        verify(userNotificationHandler).sendNotificationToUser(
                eq("alice"),
                eq(Map.of(
                        "type", "clarification_needed",
                        "suggestionId", 42L,
                        "suggestionTitle", "Add dark mode",
                        "questionCount", 2
                ))
        );
    }

    @Test
    void broadcastClarificationQuestions_nullAuthorName_doesNotSendNotification() throws Exception {
        Suggestion suggestion = new Suggestion();
        suggestion.setTitle("Feature X");
        suggestion.setAuthorName(null);

        when(suggestionRepository.findById(99L)).thenReturn(Optional.of(suggestion));

        invokeBroadcastClarificationQuestions(99L, List.of("Question?"));

        verify(userNotificationHandler, never()).sendNotificationToUser(any(), any());
    }

    @Test
    void broadcastClarificationQuestions_suggestionNotFound_doesNotSendNotification() throws Exception {
        when(suggestionRepository.findById(7L)).thenReturn(Optional.empty());

        invokeBroadcastClarificationQuestions(7L, List.of("Question?"));

        verify(userNotificationHandler, never()).sendNotificationToUser(any(), any());
    }

    @Test
    void broadcastClarificationQuestions_nullTitle_sendsEmptyStringForTitle() throws Exception {
        Suggestion suggestion = new Suggestion();
        suggestion.setTitle(null);
        suggestion.setAuthorName("bob");

        when(suggestionRepository.findById(5L)).thenReturn(Optional.of(suggestion));

        invokeBroadcastClarificationQuestions(5L, List.of("Q?"));

        verify(userNotificationHandler).sendNotificationToUser(
                eq("bob"),
                eq(Map.of(
                        "type", "clarification_needed",
                        "suggestionId", 5L,
                        "suggestionTitle", "",
                        "questionCount", 1
                ))
        );
    }

    @Test
    void broadcastClarificationQuestions_questionCountMatchesListSize() throws Exception {
        Suggestion suggestion = new Suggestion();
        suggestion.setTitle("Improve search");
        suggestion.setAuthorName("carol");

        when(suggestionRepository.findById(3L)).thenReturn(Optional.of(suggestion));

        invokeBroadcastClarificationQuestions(3L, List.of("Q1?", "Q2?", "Q3?"));

        verify(userNotificationHandler).sendNotificationToUser(
                eq("carol"),
                eq(Map.of(
                        "type", "clarification_needed",
                        "suggestionId", 3L,
                        "suggestionTitle", "Improve search",
                        "questionCount", 3
                ))
        );
    }

    // --- Slack notification trigger tests ---

    @Test
    void approveSuggestion_sendsApprovedSlackNotification() {
        Suggestion suggestion = new Suggestion();
        suggestion.setTitle("My feature");
        when(suggestionRepository.findById(10L)).thenReturn(Optional.of(suggestion));
        when(suggestionRepository.save(any())).thenReturn(suggestion);

        service.approveSuggestion(10L);

        verify(slackNotificationService).sendNotification(suggestion, "APPROVED");
    }

    @Test
    void denySuggestion_sendsDeniedSlackNotification() {
        Suggestion suggestion = new Suggestion();
        suggestion.setTitle("My feature");
        when(suggestionRepository.findById(20L)).thenReturn(Optional.of(suggestion));
        when(suggestionRepository.save(any())).thenReturn(suggestion);

        service.denySuggestion(20L, "Not aligned with goals");

        verify(slackNotificationService).sendNotification(suggestion, "DENIED");
    }

    @Test
    void denySuggestion_nullReason_sendsDeniedSlackNotification() {
        Suggestion suggestion = new Suggestion();
        suggestion.setTitle("My feature");
        when(suggestionRepository.findById(21L)).thenReturn(Optional.of(suggestion));
        when(suggestionRepository.save(any())).thenReturn(suggestion);

        service.denySuggestion(21L, null);

        verify(slackNotificationService).sendNotification(suggestion, "DENIED");
    }

    @Test
    void handleExecutionResult_completedResult_sendsDevCompleteSlackNotification() {
        Suggestion suggestion = new Suggestion();
        suggestion.setTitle("My feature");
        when(suggestionRepository.findById(30L)).thenReturn(Optional.of(suggestion));
        when(suggestionRepository.save(any())).thenReturn(suggestion);

        // Spy on PlanExecutionService (where handleExecutionResult now lives) so we
        // can stub createPrAsync to avoid its complex git/PR dependencies
        PlanExecutionService spyPes = spy(planExecutionService);
        doNothing().when(spyPes).createPrAsync(any());

        spyPes.handleExecutionResult(30L, "COMPLETED — all done");

        verify(slackNotificationService).sendNotification(eq(suggestion), eq("DEV_COMPLETE"));
    }

    @Test
    void handleExecutionResult_failedResult_doesNotSendDevCompleteNotification() {
        Suggestion suggestion = new Suggestion();
        suggestion.setTitle("My feature");
        when(suggestionRepository.findById(31L)).thenReturn(Optional.of(suggestion));
        when(suggestionRepository.save(any())).thenReturn(suggestion);

        planExecutionService.handleExecutionResult(31L, "FAILED — something went wrong");

        verify(slackNotificationService, never()).sendNotification(any(), eq("DEV_COMPLETE"));
    }

    @Test
    void handleExecutionResult_suggestionNotFound_doesNotSendAnyNotification() {
        when(suggestionRepository.findById(99L)).thenReturn(Optional.empty());

        planExecutionService.handleExecutionResult(99L, "COMPLETED");

        verify(slackNotificationService, never()).sendNotification(any(), any());
    }

    @Test
    void approveSuggestion_suggestionNotFound_throwsAndDoesNotSendNotification() {
        when(suggestionRepository.findById(50L)).thenReturn(Optional.empty());

        try {
            service.approveSuggestion(50L);
        } catch (IllegalArgumentException ignored) {
        }

        verify(slackNotificationService, never()).sendNotification(any(), any());
    }

    @Test
    void denySuggestion_suggestionNotFound_throwsAndDoesNotSendNotification() {
        when(suggestionRepository.findById(51L)).thenReturn(Optional.empty());

        try {
            service.denySuggestion(51L, "reason");
        } catch (IllegalArgumentException ignored) {
        }

        verify(slackNotificationService, never()).sendNotification(any(), any());
    }

    // --- notifyAdminsApprovalNeeded tests ---

    @Test
    void notifyAdminsApprovalNeeded_sendsSlackApprovalNotification() throws Exception {
        Suggestion suggestion = new Suggestion();
        suggestion.setTitle("Enable dark mode");

        invokeNotifyAdminsApprovalNeeded(suggestion);

        verify(slackNotificationService).sendApprovalNeededNotification(suggestion);
    }

    @Test
    void notifyAdminsApprovalNeeded_sendsWebSocketToEachAdmin() throws Exception {
        Suggestion suggestion = new Suggestion();
        suggestion.setId(55L);
        suggestion.setTitle("Feature request");

        User rootAdmin = new User();
        rootAdmin.setUsername("rootadmin");
        User admin = new User();
        admin.setUsername("adminuser");

        when(userRepository.findByRole(UserRole.ROOT_ADMIN)).thenReturn(List.of(rootAdmin));
        when(userRepository.findByRole(UserRole.ADMIN)).thenReturn(List.of(admin));

        invokeNotifyAdminsApprovalNeeded(suggestion);

        Map<String, Object> expectedPayload = Map.of(
                "type", "approval_needed",
                "suggestionId", 55L,
                "suggestionTitle", "Feature request"
        );
        verify(userNotificationHandler).sendNotificationToUser(eq("rootadmin"), eq(expectedPayload));
        verify(userNotificationHandler).sendNotificationToUser(eq("adminuser"), eq(expectedPayload));
    }

    @Test
    void notifyAdminsApprovalNeeded_noAdmins_onlySendsSlack() throws Exception {
        Suggestion suggestion = new Suggestion();
        suggestion.setId(56L);
        suggestion.setTitle("Another feature");

        when(userRepository.findByRole(UserRole.ROOT_ADMIN)).thenReturn(List.of());
        when(userRepository.findByRole(UserRole.ADMIN)).thenReturn(List.of());

        invokeNotifyAdminsApprovalNeeded(suggestion);

        verify(slackNotificationService).sendApprovalNeededNotification(suggestion);
        verify(userNotificationHandler, never()).sendNotificationToUser(any(), any());
    }

    @Test
    void notifyAdminsApprovalNeeded_nullTitle_usesEmptyString() throws Exception {
        Suggestion suggestion = new Suggestion();
        suggestion.setId(57L);
        suggestion.setTitle(null);

        User admin = new User();
        admin.setUsername("adminuser");
        when(userRepository.findByRole(UserRole.ADMIN)).thenReturn(List.of(admin));

        invokeNotifyAdminsApprovalNeeded(suggestion);

        Map<String, Object> expectedPayload = Map.of(
                "type", "approval_needed",
                "suggestionId", 57L,
                "suggestionTitle", ""
        );
        verify(userNotificationHandler).sendNotificationToUser(eq("adminuser"), eq(expectedPayload));
    }

    @Test
    void notifyAdminsApprovalNeeded_adminWithNullUsername_skipsNotification() throws Exception {
        Suggestion suggestion = new Suggestion();
        suggestion.setId(58L);
        suggestion.setTitle("Some feature");

        User adminWithNullUsername = new User();
        adminWithNullUsername.setUsername(null);
        when(userRepository.findByRole(UserRole.ADMIN)).thenReturn(List.of(adminWithNullUsername));

        invokeNotifyAdminsApprovalNeeded(suggestion);

        verify(userNotificationHandler, never()).sendNotificationToUser(any(), any());
    }

    private void invokeBroadcastClarificationQuestions(Long suggestionId, List<String> questions) throws Exception {
        Method method = SuggestionMessagingHelper.class.getDeclaredMethod(
                "broadcastClarificationQuestions", Long.class, List.class);
        method.setAccessible(true);
        method.invoke(messagingHelper, suggestionId, questions);
    }

    private void invokeNotifyAdminsApprovalNeeded(Suggestion suggestion) throws Exception {
        Method method = ExpertReviewService.class.getDeclaredMethod(
                "notifyAdminsApprovalNeeded", Suggestion.class);
        method.setAccessible(true);
        method.invoke(expertReviewService, suggestion);
    }
}
