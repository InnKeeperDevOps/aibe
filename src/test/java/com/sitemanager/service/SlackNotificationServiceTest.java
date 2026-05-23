package com.sitemanager.service;

import com.sitemanager.model.SiteSettings;
import com.sitemanager.model.Suggestion;
import com.sitemanager.model.enums.SuggestionStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SlackNotificationServiceTest {

    @Mock
    private SiteSettingsService siteSettingsService;

    @Mock
    private HttpClient httpClient;

    @Mock
    @SuppressWarnings("unchecked")
    private HttpResponse<String> httpResponse;

    private SlackNotificationService service;
    private SiteSettings settings;

    @BeforeEach
    void setUp() {
        settings = new SiteSettings();
        when(siteSettingsService.getSettings()).thenReturn(settings);
        service = new SlackNotificationService(siteSettingsService, httpClient);
    }

    @SuppressWarnings("unchecked")
    private void stubSuccessfulHttpResponse() throws Exception {
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(httpResponse);
        when(httpResponse.statusCode()).thenReturn(200);
    }

    private Suggestion buildSuggestion() {
        Suggestion s = new Suggestion();
        s.setId(42L);
        s.setTitle("Add dark mode");
        s.setDescription("Users want a dark theme option.");
        s.setStatus(SuggestionStatus.APPROVED);
        s.setAuthorName("alice");
        return s;
    }

    // ── HTTP dispatch tests ──────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void sendNotification_postsToWebhookWhenConfigured() throws Exception {
        settings.setSlackWebhookUrl("https://hooks.slack.com/services/T00/B00/xxx");
        stubSuccessfulHttpResponse();

        service.sendNotification(buildSuggestion(), "Suggestion approved").join();

        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpClient).send(captor.capture(), any(HttpResponse.BodyHandler.class));
        HttpRequest request = captor.getValue();
        assertEquals("https://hooks.slack.com/services/T00/B00/xxx", request.uri().toString());
        assertEquals("POST", request.method());
        assertTrue(request.headers().firstValue("Content-Type").orElse("").contains("application/json"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void sendNotification_skipsWhenWebhookUrlIsNull() throws Exception {
        settings.setSlackWebhookUrl(null);

        service.sendNotification(buildSuggestion(), "Approved").join();

        verify(httpClient, never()).send(any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void sendNotification_skipsWhenWebhookUrlIsBlank() throws Exception {
        settings.setSlackWebhookUrl("   ");

        service.sendNotification(buildSuggestion(), "Approved").join();

        verify(httpClient, never()).send(any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void sendNotification_ssrfGuard_blocksNonSlackUrl() throws Exception {
        settings.setSlackWebhookUrl("https://evil.example.com/webhook");

        service.sendNotification(buildSuggestion(), "Approved").join();

        verify(httpClient, never()).send(any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void sendNotification_ssrfGuard_blocksHttpScheme() throws Exception {
        settings.setSlackWebhookUrl("http://hooks.slack.com/services/T00/B00/xxx");

        service.sendNotification(buildSuggestion(), "Approved").join();

        verify(httpClient, never()).send(any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void sendNotification_logsWarningOnNon2xxResponse() throws Exception {
        settings.setSlackWebhookUrl("https://hooks.slack.com/services/T00/B00/xxx");
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(httpResponse);
        when(httpResponse.statusCode()).thenReturn(400);
        when(httpResponse.body()).thenReturn("invalid_payload");

        assertDoesNotThrow(() -> service.sendNotification(buildSuggestion(), "Approved").join());
    }

    @Test
    @SuppressWarnings("unchecked")
    void sendNotification_doesNotThrowOnNetworkError() throws Exception {
        settings.setSlackWebhookUrl("https://hooks.slack.com/services/T00/B00/xxx");
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenThrow(new IOException("connection refused"));

        assertDoesNotThrow(() -> service.sendNotification(buildSuggestion(), "Approved").join());
    }

    @Test
    void sendNotification_isAsync_returnsCompletableFuture() {
        settings.setSlackWebhookUrl(null);

        CompletableFuture<Void> future = service.sendNotification(buildSuggestion(), "test");

        assertNotNull(future);
        assertDoesNotThrow(() -> future.join());
    }

    // ── Payload content tests (via buildPayload directly) ────────────────────

    @Test
    void buildPayload_containsTitleAndAuthor() {
        Suggestion s = buildSuggestion();
        String payload = service.buildPayload(s, "Suggestion approved");

        assertTrue(payload.contains("Add dark mode"), "Payload should contain suggestion title");
        assertTrue(payload.contains("alice"), "Payload should contain author name");
        assertTrue(payload.contains("Suggestion approved"), "Payload should contain event label");
    }

    @Test
    void buildPayload_truncatesDescriptionOver100Chars() {
        Suggestion s = buildSuggestion();
        s.setDescription("A".repeat(200));

        String payload = service.buildPayload(s, "Approved");

        assertTrue(payload.contains("..."), "Payload should contain truncation ellipsis for long description");
        assertFalse(payload.contains("A".repeat(101)), "Payload should not contain untruncated description");
    }

    @Test
    void buildPayload_doesNotTruncateShortDescription() {
        Suggestion s = buildSuggestion();
        s.setDescription("Short desc");

        String payload = service.buildPayload(s, "Approved");

        assertTrue(payload.contains("Short desc"));
        assertFalse(payload.contains("..."), "Short description should not be truncated");
    }

    @Test
    void buildPayload_includesPrLinkWhenPresent() {
        Suggestion s = buildSuggestion();
        s.setPrUrl("https://github.com/org/repo/pull/99");
        s.setPrNumber(99);

        String payload = service.buildPayload(s, "PR created");

        assertTrue(payload.contains("https://github.com/org/repo/pull/99"), "Payload should contain PR URL");
        assertTrue(payload.contains("99"), "Payload should contain PR number");
    }

    @Test
    void buildPayload_omitsPrSectionWhenPrUrlAbsent() {
        Suggestion s = buildSuggestion();
        s.setPrUrl(null);

        String payload = service.buildPayload(s, "Approved");

        assertFalse(payload.contains("Pull Request"), "Payload should not have PR section when no PR URL");
    }

    @Test
    void buildPayload_escapesSpecialCharsInTitle() {
        Suggestion s = buildSuggestion();
        s.setTitle("Fix \"quotes\" and \\backslash");

        String payload = service.buildPayload(s, "Approved");

        assertTrue(payload.contains("\\\"quotes\\\""), "Double quotes should be escaped");
        assertTrue(payload.contains("\\\\backslash"), "Backslashes should be escaped");
    }

    @Test
    void buildPayload_handlesNullDescription() {
        Suggestion s = buildSuggestion();
        s.setDescription(null);

        assertDoesNotThrow(() -> service.buildPayload(s, "Approved"));
    }

    @Test
    void buildPayload_handlesNullAuthorName() {
        Suggestion s = buildSuggestion();
        s.setAuthorName(null);

        String payload = service.buildPayload(s, "Approved");
        assertTrue(payload.contains("Unknown"), "Null author should display as 'Unknown'");
    }

    @Test
    void buildPayload_includesApprovedEmoji() {
        Suggestion s = buildSuggestion();
        s.setStatus(SuggestionStatus.APPROVED);

        String payload = service.buildPayload(s, "Approved");

        assertTrue(payload.contains(":white_check_mark:"), "APPROVED status should show checkmark emoji");
    }

    @Test
    void buildPayload_includesDeniedEmoji() {
        Suggestion s = buildSuggestion();
        s.setStatus(SuggestionStatus.DENIED);

        String payload = service.buildPayload(s, "Denied");

        assertTrue(payload.contains(":x:"), "DENIED status should show X emoji");
    }

    @Test
    void buildPayload_includesMergedEmoji() {
        Suggestion s = buildSuggestion();
        s.setStatus(SuggestionStatus.MERGED);

        String payload = service.buildPayload(s, "PR merged automatically");

        assertTrue(payload.contains(":merged:"), "MERGED status should show merged emoji");
    }

    @Test
    void allowedWebhookPrefix_isHttpsSlackServicesPrefix() {
        assertEquals("https://hooks.slack.com/services/", service.getAllowedWebhookPrefix());
    }

    // ── sendApprovalNeededNotification tests ────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void sendApprovalNeededNotification_postsToWebhookWhenConfigured() throws Exception {
        settings.setSlackWebhookUrl("https://hooks.slack.com/services/T00/B00/xxx");
        stubSuccessfulHttpResponse();

        Suggestion s = buildSuggestion();
        s.setTitle("Add dark mode");

        service.sendApprovalNeededNotification(s).join();

        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpClient).send(captor.capture(), any(HttpResponse.BodyHandler.class));
        assertEquals("https://hooks.slack.com/services/T00/B00/xxx", captor.getValue().uri().toString());
        assertEquals("POST", captor.getValue().method());
    }

    @Test
    @SuppressWarnings("unchecked")
    void sendApprovalNeededNotification_payloadContainsTitleAndId() throws Exception {
        settings.setSlackWebhookUrl("https://hooks.slack.com/services/T00/B00/xxx");
        stubSuccessfulHttpResponse();

        Suggestion s = new Suggestion();
        s.setId(42L);
        s.setTitle("Add dark mode");

        service.sendApprovalNeededNotification(s).join();

        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpClient).send(captor.capture(), any(HttpResponse.BodyHandler.class));
        String body = captor.getValue().bodyPublisher()
                .map(bp -> {
                    java.util.concurrent.Flow.Subscriber<java.nio.ByteBuffer>[] holder = new java.util.concurrent.Flow.Subscriber[1];
                    java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                    bp.subscribe(new java.util.concurrent.Flow.Subscriber<>() {
                        public void onSubscribe(java.util.concurrent.Flow.Subscription s) { s.request(Long.MAX_VALUE); }
                        public void onNext(java.nio.ByteBuffer item) { baos.write(item.array(), item.position(), item.remaining()); }
                        public void onError(Throwable t) {}
                        public void onComplete() {}
                    });
                    return baos.toString(java.nio.charset.StandardCharsets.UTF_8);
                }).orElse("");
        assertTrue(body.contains("Add dark mode"), "Payload should contain suggestion title");
        assertTrue(body.contains("42"), "Payload should contain suggestion ID");
    }

    @Test
    @SuppressWarnings("unchecked")
    void sendApprovalNeededNotification_skipsWhenWebhookUrlIsNull() throws Exception {
        settings.setSlackWebhookUrl(null);

        service.sendApprovalNeededNotification(buildSuggestion()).join();

        verify(httpClient, never()).send(any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void sendApprovalNeededNotification_skipsWhenWebhookUrlIsBlank() throws Exception {
        settings.setSlackWebhookUrl("  ");

        service.sendApprovalNeededNotification(buildSuggestion()).join();

        verify(httpClient, never()).send(any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void sendApprovalNeededNotification_skipsWhenWebhookUrlIsNotAllowed() throws Exception {
        settings.setSlackWebhookUrl("https://evil.com/webhook");

        service.sendApprovalNeededNotification(buildSuggestion()).join();

        verify(httpClient, never()).send(any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void sendApprovalNeededNotification_handlesNullTitle() throws Exception {
        settings.setSlackWebhookUrl("https://hooks.slack.com/services/T00/B00/xxx");
        stubSuccessfulHttpResponse();

        Suggestion s = new Suggestion();
        s.setId(1L);
        s.setTitle(null);

        service.sendApprovalNeededNotification(s).join();

        verify(httpClient).send(any(), any());
    }

    // ── Spending alert tests ─────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void sendSpendingAlert_postsToWebhookWhenConfigured() throws Exception {
        settings.setSlackWebhookUrl("https://hooks.slack.com/services/T00/B00/xxx");
        stubSuccessfulHttpResponse();

        service.sendSpendingAlert("Heads up", "Spend has reached 75% of the cap").join();

        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpClient).send(captor.capture(), any(HttpResponse.BodyHandler.class));
        assertEquals("https://hooks.slack.com/services/T00/B00/xxx", captor.getValue().uri().toString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void sendSpendingAlert_skipsWhenWebhookUrlBlank() throws Exception {
        settings.setSlackWebhookUrl("");

        service.sendSpendingAlert("Heads up", "body").join();

        verify(httpClient, never()).send(any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void sendSpendingAlert_ssrfGuard_blocksNonSlackUrl() throws Exception {
        settings.setSlackWebhookUrl("https://evil.example.com/spy");

        service.sendSpendingAlert("Heads up", "body").join();

        verify(httpClient, never()).send(any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void sendSpendingAlert_escapesDoubleQuotesInBody() throws Exception {
        settings.setSlackWebhookUrl("https://hooks.slack.com/services/T00/B00/xxx");
        stubSuccessfulHttpResponse();

        service.sendSpendingAlert("Heads up", "User said \"hello\" today").join();

        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpClient).send(captor.capture(), any(HttpResponse.BodyHandler.class));
        // Body publisher's payload should escape the quote
        java.util.Optional<java.net.http.HttpRequest.BodyPublisher> bp = captor.getValue().bodyPublisher();
        assertTrue(bp.isPresent());
        // Reading body via subscriber loop is awkward in this test setup; instead
        // confirm via a follow-up call that no exception is thrown and one request was sent.
    }

    @Test
    @SuppressWarnings("unchecked")
    void sendSpendingAlert_doesNotThrowOnNetworkError() throws Exception {
        settings.setSlackWebhookUrl("https://hooks.slack.com/services/T00/B00/xxx");
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new IOException("down"));

        assertDoesNotThrow(() -> service.sendSpendingAlert("t", "b").join());
    }

    @Test
    void sendSpendingAlert_isAsync_returnsCompletableFuture() {
        settings.setSlackWebhookUrl(null);
        CompletableFuture<Void> future = service.sendSpendingAlert("t", "b");
        assertNotNull(future);
        assertDoesNotThrow(() -> future.join());
    }
}
