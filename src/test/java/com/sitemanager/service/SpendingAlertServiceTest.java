package com.sitemanager.service;

import com.sitemanager.model.SiteSettings;
import com.sitemanager.model.SpendingAlertState;
import com.sitemanager.model.Suggestion;
import com.sitemanager.model.User;
import com.sitemanager.model.enums.CostResetPeriod;
import com.sitemanager.model.enums.UserRole;
import com.sitemanager.repository.ExpertReviewCostRepository;
import com.sitemanager.repository.SpendingAlertStateRepository;
import com.sitemanager.repository.SuggestionRepository;
import com.sitemanager.repository.UserRepository;
import com.sitemanager.websocket.UserNotificationWebSocketHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link SpendingAlertService}: warning thresholds are fired the
 * first time spend crosses them, the cap-reached alert is fired separately,
 * duplicates are suppressed, recipients come from the admin role list plus
 * any server-side configured list (never from request input), and
 * user-supplied suggestion content is sanitized before being placed in any
 * outbound message.
 */
class SpendingAlertServiceTest {

    private SiteSettingsService settingsService;
    private ExpertReviewCostRepository costRepository;
    private SpendingAlertStateRepository alertStateRepository;
    private SuggestionRepository suggestionRepository;
    private UserRepository userRepository;
    private SlackNotificationService slackNotificationService;
    private UserNotificationWebSocketHandler userNotificationHandler;
    private SiteSettings settings;
    private SpendingAlertService service;

    @BeforeEach
    void setUp() {
        settingsService = mock(SiteSettingsService.class);
        costRepository = mock(ExpertReviewCostRepository.class);
        alertStateRepository = mock(SpendingAlertStateRepository.class);
        suggestionRepository = mock(SuggestionRepository.class);
        userRepository = mock(UserRepository.class);
        slackNotificationService = mock(SlackNotificationService.class);
        userNotificationHandler = mock(UserNotificationWebSocketHandler.class);

        settings = new SiteSettings();
        when(settingsService.getSettings()).thenReturn(settings);
        when(slackNotificationService.sendSpendingAlert(any(), any()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(alertStateRepository.findByScopeAndScopeKeyAndWindowKeyAndThresholdPercent(
                any(), any(), any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(Optional.empty());
        when(alertStateRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(userRepository.findAll()).thenReturn(List.of());

        service = new SpendingAlertService(
                settingsService,
                costRepository,
                alertStateRepository,
                suggestionRepository,
                userRepository,
                slackNotificationService,
                userNotificationHandler);
    }

    // -------------------------------------------------------------------------
    // Threshold-crossing detection
    // -------------------------------------------------------------------------

    @Test
    void thresholdsCrossed_returnsAllReachedThresholdsPlusCapWhenAtLimit() {
        List<Integer> crossed = SpendingAlertService.thresholdsCrossed(
                new BigDecimal("10.00"), new BigDecimal("10.00"), List.of(75, 90));
        assertThat(crossed).containsExactly(75, 90, 100);
    }

    @Test
    void thresholdsCrossed_returnsOnlyLowestWhenSpendBetweenThresholds() {
        List<Integer> crossed = SpendingAlertService.thresholdsCrossed(
                new BigDecimal("8.00"), new BigDecimal("10.00"), List.of(75, 90));
        assertThat(crossed).containsExactly(75);
    }

    @Test
    void thresholdsCrossed_returnsEmptyWhenSpendBelowAllThresholds() {
        List<Integer> crossed = SpendingAlertService.thresholdsCrossed(
                new BigDecimal("3.00"), new BigDecimal("10.00"), List.of(75, 90));
        assertThat(crossed).isEmpty();
    }

    @Test
    void thresholdsCrossed_doesNotRoundUpFractionsToHitThreshold() {
        // 74.99% must NOT count as having crossed 75%.
        List<Integer> crossed = SpendingAlertService.thresholdsCrossed(
                new BigDecimal("7.499"), new BigDecimal("10.00"), List.of(75, 90));
        assertThat(crossed).isEmpty();
    }

    @Test
    void thresholdsCrossed_capReachedFiresOn100PercentExactly() {
        List<Integer> crossed = SpendingAlertService.thresholdsCrossed(
                new BigDecimal("10.00"), new BigDecimal("10.00"), List.of());
        assertThat(crossed).containsExactly(100);
    }

    @Test
    void thresholdsCrossed_capReachedFiresOnOvershoot() {
        List<Integer> crossed = SpendingAlertService.thresholdsCrossed(
                new BigDecimal("11.50"), new BigDecimal("10.00"), List.of(75, 90));
        assertThat(crossed).containsExactly(75, 90, 100);
    }

    @Test
    void thresholdsCrossed_ignoresOutOfRangeWarningThresholds() {
        List<Integer> crossed = SpendingAlertService.thresholdsCrossed(
                new BigDecimal("9.50"), new BigDecimal("10.00"), List.of(0, 100, 200, 90, -5));
        // Only the valid 90 should be considered; 100 still appears on its own
        // as the cap-reached event because we're at 95%, not yet at 100.
        assertThat(crossed).containsExactly(90);
    }

    @Test
    void thresholdsCrossed_zeroOrNegativeLimitReturnsEmpty() {
        assertThat(SpendingAlertService.thresholdsCrossed(
                new BigDecimal("5"), BigDecimal.ZERO, List.of(75, 90))).isEmpty();
        assertThat(SpendingAlertService.thresholdsCrossed(
                new BigDecimal("5"), new BigDecimal("-1"), List.of(75, 90))).isEmpty();
    }

    @Test
    void thresholdsCrossed_nullSpendReturnsEmpty() {
        assertThat(SpendingAlertService.thresholdsCrossed(
                null, new BigDecimal("10"), List.of(75, 90))).isEmpty();
    }

    // -------------------------------------------------------------------------
    // Threshold configuration parsing
    // -------------------------------------------------------------------------

    @Test
    void parseThresholds_usesDefaultWhenBlank() {
        assertThat(SpendingAlertService.parseThresholds(null))
                .isEqualTo(SpendingAlertService.defaultThresholds());
        assertThat(SpendingAlertService.parseThresholds(""))
                .isEqualTo(SpendingAlertService.defaultThresholds());
        assertThat(SpendingAlertService.parseThresholds("   "))
                .isEqualTo(SpendingAlertService.defaultThresholds());
    }

    @Test
    void parseThresholds_acceptsCommaSeparated() {
        assertThat(SpendingAlertService.parseThresholds("50, 75, 95"))
                .containsExactly(50, 75, 95);
    }

    @Test
    void parseThresholds_deduplicatesAndSortsAscending() {
        assertThat(SpendingAlertService.parseThresholds("90,75,75,50"))
                .containsExactly(50, 75, 90);
    }

    @Test
    void parseThresholds_rejectsOutOfRangeAndMalformedValues() {
        assertThat(SpendingAlertService.parseThresholds("0, 100, 101, -5, abc, 50"))
                .containsExactly(50);
    }

    @Test
    void parseThresholds_fallsBackToDefaultWhenNothingValid() {
        assertThat(SpendingAlertService.parseThresholds("0, 100, abc"))
                .isEqualTo(SpendingAlertService.defaultThresholds());
    }

    // -------------------------------------------------------------------------
    // Window-key construction
    // -------------------------------------------------------------------------

    @Test
    void globalWindowKey_neverPeriodIsConstant() {
        assertThat(SpendingAlertService.globalWindowKey(CostResetPeriod.NEVER))
                .isEqualTo("NEVER");
    }

    @Test
    void globalWindowKey_dailyPeriodEncodesCurrentDate() {
        String key = SpendingAlertService.globalWindowKey(CostResetPeriod.DAILY);
        assertThat(key).startsWith("DAILY:");
        // 10-char ISO date follows the prefix
        assertThat(key.substring("DAILY:".length())).hasSize(10);
    }

    @Test
    void globalWindowKey_monthlyPeriodEncodesCurrentMonth() {
        String key = SpendingAlertService.globalWindowKey(CostResetPeriod.MONTHLY);
        assertThat(key).startsWith("MONTHLY:");
        assertThat(key.substring("MONTHLY:".length())).hasSize(7); // yyyy-MM
    }

    // -------------------------------------------------------------------------
    // End-to-end evaluation: per-suggestion
    // -------------------------------------------------------------------------

    @Test
    void evaluate_doesNothingWhenAlertsDisabled() {
        settings.setSpendingAlertsEnabled(false);
        settings.setMaxCostPerSuggestionUsd(new BigDecimal("10"));
        when(costRepository.sumCostBySuggestionId(1L)).thenReturn(new BigDecimal("9"));

        service.evaluateAfterCostRecorded(1L);

        verify(slackNotificationService, never()).sendSpendingAlert(any(), any());
        verify(alertStateRepository, never()).save(any());
    }

    @Test
    void evaluate_perSuggestionWarningThresholdFires_recordsState_andSendsAlerts() {
        settings.setMaxCostPerSuggestionUsd(new BigDecimal("10"));
        settings.setSpendingAlertThresholds("75,90");
        when(costRepository.sumCostBySuggestionId(7L)).thenReturn(new BigDecimal("7.50"));
        when(suggestionRepository.findById(7L)).thenReturn(Optional.of(suggestion(7L, "Refactor checkout flow")));
        when(userRepository.findAll()).thenReturn(List.of(admin("root"), regular("bob")));

        service.evaluateAfterCostRecorded(7L);

        ArgumentCaptor<SpendingAlertState> stateCaptor = ArgumentCaptor.forClass(SpendingAlertState.class);
        verify(alertStateRepository).save(stateCaptor.capture());
        SpendingAlertState saved = stateCaptor.getValue();
        assertThat(saved.getScope()).isEqualTo(SpendingAlertState.Scope.PER_SUGGESTION);
        assertThat(saved.getThresholdPercent()).isEqualTo(75);
        assertThat(saved.getScopeKey()).isEqualTo("7");
        assertThat(saved.getWindowKey()).isEqualTo(SpendingAlertService.lifetimeWindow());
        assertThat(saved.getSpendAtAlert()).isEqualByComparingTo("7.50");
        assertThat(saved.getLimitAtAlert()).isEqualByComparingTo("10");

        verify(slackNotificationService).sendSpendingAlert(anyString(), anyString());
        verify(userNotificationHandler).sendNotificationToUser(eq("root"), any());
        verify(userNotificationHandler, never()).sendNotificationToUser(eq("bob"), any());
    }

    @Test
    void evaluate_perSuggestion_doesNotFireWhenAlreadyRecordedForWindow() {
        settings.setMaxCostPerSuggestionUsd(new BigDecimal("10"));
        settings.setSpendingAlertThresholds("75");
        when(costRepository.sumCostBySuggestionId(7L)).thenReturn(new BigDecimal("8"));
        when(alertStateRepository.findByScopeAndScopeKeyAndWindowKeyAndThresholdPercent(
                SpendingAlertState.Scope.PER_SUGGESTION, "7", SpendingAlertService.lifetimeWindow(), 75))
                .thenReturn(Optional.of(new SpendingAlertState()));

        service.evaluateAfterCostRecorded(7L);

        verify(alertStateRepository, never()).save(any());
        verify(slackNotificationService, never()).sendSpendingAlert(any(), any());
    }

    @Test
    void evaluate_capReachedFiresSeparatelyFromWarningThresholds() {
        settings.setMaxCostPerSuggestionUsd(new BigDecimal("10"));
        settings.setSpendingAlertThresholds("75,90");
        when(costRepository.sumCostBySuggestionId(3L)).thenReturn(new BigDecimal("10.50"));

        service.evaluateAfterCostRecorded(3L);

        // 75, 90, and 100 should each be saved as their own row — three distinct alerts.
        verify(alertStateRepository, org.mockito.Mockito.times(3)).save(any());
        verify(slackNotificationService, org.mockito.Mockito.times(3))
                .sendSpendingAlert(anyString(), anyString());
    }

    @Test
    void evaluate_perSuggestion_skipsWhenNoLimitConfigured() {
        when(costRepository.sumCostBySuggestionId(7L)).thenReturn(new BigDecimal("1000"));

        service.evaluateAfterCostRecorded(7L);

        verify(alertStateRepository, never()).save(any());
    }

    @Test
    void evaluate_perSuggestion_skipsWhenSuggestionIdNull() {
        settings.setMaxCostPerSuggestionUsd(new BigDecimal("10"));

        service.evaluateAfterCostRecorded(null);

        verify(costRepository, never()).sumCostBySuggestionId(any());
        verify(alertStateRepository, never()).save(any());
    }

    @Test
    void evaluate_alertBodyIsSanitized_titleHasInjectedHtml() {
        settings.setMaxCostPerSuggestionUsd(new BigDecimal("10"));
        settings.setSpendingAlertThresholds("75");
        when(costRepository.sumCostBySuggestionId(99L)).thenReturn(new BigDecimal("8"));
        when(suggestionRepository.findById(99L))
                .thenReturn(Optional.of(suggestion(99L, "<script>alert('xss')</script>")));

        service.evaluateAfterCostRecorded(99L);

        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(slackNotificationService).sendSpendingAlert(anyString(), bodyCaptor.capture());
        String body = bodyCaptor.getValue();
        // Raw HTML must not appear; the sanitized form (HTML-escaped) must.
        assertThat(body).doesNotContain("<script>");
        assertThat(body).contains("&lt;script&gt;");
    }

    @Test
    void evaluate_doesNotThrowWhenStateSaveFailsDueToRace() {
        settings.setMaxCostPerSuggestionUsd(new BigDecimal("10"));
        settings.setSpendingAlertThresholds("75");
        when(costRepository.sumCostBySuggestionId(1L)).thenReturn(new BigDecimal("8"));
        when(alertStateRepository.save(any()))
                .thenThrow(new DataIntegrityViolationException("duplicate key"));

        // Must not propagate — alerts are best-effort.
        service.evaluateAfterCostRecorded(1L);

        // The duplicate save should have aborted dispatch; no Slack call expected.
        verify(slackNotificationService, never()).sendSpendingAlert(any(), any());
    }

    // -------------------------------------------------------------------------
    // End-to-end evaluation: global cap
    // -------------------------------------------------------------------------

    @Test
    void evaluate_globalWarningFires_andSavesGlobalWindowKey() {
        settings.setMaxTotalCostUsd(new BigDecimal("100"));
        settings.setGlobalCostResetPeriod(CostResetPeriod.DAILY);
        settings.setSpendingAlertThresholds("75");
        when(costRepository.sumCostSince(any())).thenReturn(new BigDecimal("80"));
        when(userRepository.findAll()).thenReturn(List.of(admin("alice")));

        service.evaluateAfterCostRecorded(null);

        ArgumentCaptor<SpendingAlertState> stateCaptor = ArgumentCaptor.forClass(SpendingAlertState.class);
        verify(alertStateRepository).save(stateCaptor.capture());
        SpendingAlertState saved = stateCaptor.getValue();
        assertThat(saved.getScope()).isEqualTo(SpendingAlertState.Scope.GLOBAL);
        assertThat(saved.getScopeKey()).isEqualTo("GLOBAL");
        assertThat(saved.getWindowKey()).startsWith("DAILY:");
        assertThat(saved.getThresholdPercent()).isEqualTo(75);

        verify(slackNotificationService).sendSpendingAlert(anyString(), anyString());
        verify(userNotificationHandler).sendNotificationToUser(eq("alice"), any());
    }

    @Test
    void evaluate_globalCapReachedMessageMentionsResetWindowAndOptionToRaiseLimit() {
        settings.setMaxTotalCostUsd(new BigDecimal("50"));
        settings.setGlobalCostResetPeriod(CostResetPeriod.MONTHLY);
        when(costRepository.sumCostSince(any())).thenReturn(new BigDecimal("50"));

        service.evaluateAfterCostRecorded(null);

        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(slackNotificationService, atLeastOnce()).sendSpendingAlert(anyString(), bodyCaptor.capture());
        boolean monthlyMentioned = bodyCaptor.getAllValues().stream()
                .anyMatch(b -> b.toLowerCase().contains("month"));
        boolean raiseMentioned = bodyCaptor.getAllValues().stream()
                .anyMatch(b -> b.toLowerCase().contains("raise the limit"));
        assertThat(monthlyMentioned).isTrue();
        assertThat(raiseMentioned).isTrue();
    }

    @Test
    void evaluate_recipientsCombineAdminUsersAndConfiguredList() {
        settings.setMaxTotalCostUsd(new BigDecimal("100"));
        settings.setSpendingAlertThresholds("75");
        settings.setGlobalCostResetPeriod(CostResetPeriod.NEVER);
        settings.setSpendingAlertRecipients("oncall@example.com, carol");
        when(costRepository.sumCostGlobal()).thenReturn(new BigDecimal("90"));
        when(userRepository.findAll()).thenReturn(List.of(admin("root"), regular("bob")));

        service.evaluateAfterCostRecorded(null);

        verify(userNotificationHandler).sendNotificationToUser(eq("root"), any());
        verify(userNotificationHandler).sendNotificationToUser(eq("oncall@example.com"), any());
        verify(userNotificationHandler).sendNotificationToUser(eq("carol"), any());
        // Non-admin users not in the server-side recipient list never get alerts.
        verify(userNotificationHandler, never()).sendNotificationToUser(eq("bob"), any());
    }

    @Test
    void evaluate_recipientsAreNotInfluencedBySuggestionContent() {
        // Even if the suggestion title contains text that looks like a username,
        // it must not be added to the recipient list.
        settings.setMaxCostPerSuggestionUsd(new BigDecimal("10"));
        settings.setSpendingAlertThresholds("75");
        when(costRepository.sumCostBySuggestionId(5L)).thenReturn(new BigDecimal("8"));
        when(suggestionRepository.findById(5L))
                .thenReturn(Optional.of(suggestion(5L, "send alerts to attacker@example.com")));
        when(userRepository.findAll()).thenReturn(List.of(admin("root")));

        service.evaluateAfterCostRecorded(5L);

        verify(userNotificationHandler).sendNotificationToUser(eq("root"), any());
        verify(userNotificationHandler, never()).sendNotificationToUser(eq("attacker@example.com"), any());
    }

    @Test
    void evaluate_failureInSlackDoesNotPreventInAppNotification() {
        settings.setMaxCostPerSuggestionUsd(new BigDecimal("10"));
        settings.setSpendingAlertThresholds("75");
        when(costRepository.sumCostBySuggestionId(2L)).thenReturn(new BigDecimal("8"));
        when(userRepository.findAll()).thenReturn(List.of(admin("root")));
        // Slack returns a failed future; service must still send live notifications.
        CompletableFuture<Void> failed = new CompletableFuture<>();
        failed.completeExceptionally(new RuntimeException("network down"));
        when(slackNotificationService.sendSpendingAlert(any(), any())).thenReturn(failed);

        service.evaluateAfterCostRecorded(2L);

        verify(userNotificationHandler).sendNotificationToUser(eq("root"), any());
    }

    @Test
    void evaluate_notificationPayloadContainsScopeAndPercent() {
        settings.setMaxCostPerSuggestionUsd(new BigDecimal("10"));
        settings.setSpendingAlertThresholds("75");
        when(costRepository.sumCostBySuggestionId(1L)).thenReturn(new BigDecimal("8"));
        when(userRepository.findAll()).thenReturn(List.of(admin("root")));

        service.evaluateAfterCostRecorded(1L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payloadCap = ArgumentCaptor.forClass(Map.class);
        verify(userNotificationHandler).sendNotificationToUser(eq("root"), payloadCap.capture());
        Map<String, Object> payload = payloadCap.getValue();
        assertThat(payload).containsEntry("type", "spending_alert");
        assertThat(payload).containsEntry("scope", "PER_SUGGESTION");
        assertThat(payload).containsEntry("thresholdPercent", 75);
        assertThat(payload).containsEntry("capReached", false);
        assertThat(payload).containsKey("title");
        assertThat(payload).containsKey("body");
        assertThat(payload).containsEntry("suggestionId", 1L);
    }

    // -------------------------------------------------------------------------
    // Resets
    // -------------------------------------------------------------------------

    @Test
    void resetPerSuggestionAlerts_delegatesToRepository() {
        service.resetPerSuggestionAlerts(42L);
        verify(alertStateRepository).deletePerSuggestionAlerts("42");
    }

    @Test
    void resetGlobalAlerts_delegatesToRepository() {
        service.resetGlobalAlerts();
        verify(alertStateRepository).deleteAllGlobalAlerts();
    }

    @Test
    void resetPerSuggestionAlerts_swallowsRepositoryFailure() {
        org.mockito.Mockito.doThrow(new RuntimeException("DB down"))
                .when(alertStateRepository).deletePerSuggestionAlerts(any());
        // Must not throw — the calling settings-update flow must complete.
        service.resetPerSuggestionAlerts(1L);
    }

    @Test
    void getWarningThresholds_reflectsSettingsValue() {
        settings.setSpendingAlertThresholds("60,80,95");
        assertThat(service.getWarningThresholds(settings))
                .containsExactly(60, 80, 95);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private Suggestion suggestion(Long id, String title) {
        Suggestion s = new Suggestion();
        s.setId(id);
        s.setTitle(title);
        return s;
    }

    private User admin(String username) {
        User u = new User();
        u.setUsername(username);
        u.setRole(UserRole.ROOT_ADMIN);
        return u;
    }

    private User regular(String username) {
        User u = new User();
        u.setUsername(username);
        u.setRole(UserRole.USER);
        return u;
    }
}
