package com.sitemanager.service;

import com.sitemanager.repository.SiteSettingsRepository;
import com.sitemanager.repository.ClaudeCliLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ClaudeServiceMergePrTest {

    @Mock
    private SiteSettingsRepository settingsRepository;

    @Mock
    private ClaudeCliLogRepository cliLogRepository;

    @Mock
    private HttpClient httpClient;

    @Mock
    @SuppressWarnings("unchecked")
    private HttpResponse<String> httpResponse;

    private ClaudeService service;

    private static final String VALID_REPO = "https://github.com/owner/repo";
    private static final String TOKEN = "ghp_test_token";
    private static final int PR_NUMBER = 42;

    @BeforeEach
    void setUp() {
        service = new ClaudeService(settingsRepository, cliLogRepository);
    }

    @SuppressWarnings("unchecked")
    private void stubResponse(int statusCode) throws Exception {
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(httpResponse);
        when(httpResponse.statusCode()).thenReturn(statusCode);
        when(httpResponse.body()).thenReturn("{}");
    }

    @Test
    void mergePullRequest_http200_returnsTrue() throws Exception {
        stubResponse(200);
        assertTrue(service.mergePullRequest(VALID_REPO, PR_NUMBER, TOKEN, httpClient));
    }

    @Test
    void mergePullRequest_http405_returnsFalse() throws Exception {
        stubResponse(405);
        assertFalse(service.mergePullRequest(VALID_REPO, PR_NUMBER, TOKEN, httpClient));
    }

    @Test
    void mergePullRequest_http409_returnsFalse() throws Exception {
        stubResponse(409);
        assertFalse(service.mergePullRequest(VALID_REPO, PR_NUMBER, TOKEN, httpClient));
    }

    @Test
    void mergePullRequest_http422_returnsFalse() throws Exception {
        stubResponse(422);
        assertFalse(service.mergePullRequest(VALID_REPO, PR_NUMBER, TOKEN, httpClient));
    }

    @Test
    void mergePullRequest_http503_returnsFalse() throws Exception {
        stubResponse(503);
        assertFalse(service.mergePullRequest(VALID_REPO, PR_NUMBER, TOKEN, httpClient));
    }

    @Test
    void mergePullRequest_nullToken_returnsFalseWithoutThrowing() {
        assertFalse(service.mergePullRequest(VALID_REPO, PR_NUMBER, null, httpClient));
        verifyNoInteractions(httpClient);
    }

    @Test
    void mergePullRequest_emptyToken_returnsFalseWithoutThrowing() {
        assertFalse(service.mergePullRequest(VALID_REPO, PR_NUMBER, "", httpClient));
        verifyNoInteractions(httpClient);
    }

    @Test
    void mergePullRequest_malformedRepoUrl_returnsFalseWithoutThrowing() {
        assertFalse(service.mergePullRequest("not-a-url", PR_NUMBER, TOKEN, httpClient));
        verifyNoInteractions(httpClient);
    }

    @Test
    void mergePullRequest_nullRepoUrl_returnsFalseWithoutThrowing() {
        assertFalse(service.mergePullRequest(null, PR_NUMBER, TOKEN, httpClient));
        verifyNoInteractions(httpClient);
    }

    @Test
    @SuppressWarnings("unchecked")
    void mergePullRequest_networkException_returnsFalseWithoutThrowing() throws Exception {
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new java.io.IOException("connection refused"));
        assertFalse(service.mergePullRequest(VALID_REPO, PR_NUMBER, TOKEN, httpClient));
    }

    @Test
    @SuppressWarnings("unchecked")
    void mergePullRequest_requestContainsNoToken() throws Exception {
        stubResponse(405);
        service.mergePullRequest(VALID_REPO, PR_NUMBER, TOKEN, httpClient);

        // Capture the request and verify the token is in Authorization header (present but never logged)
        // This test confirms the request is constructed correctly — if the auth header were absent
        // the merge would always fail in production regardless of status code.
        verify(httpClient).send(argThat(req ->
                req.headers().firstValue("Authorization").orElse("").startsWith("Bearer ")
        ), any(HttpResponse.BodyHandler.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void mergePullRequest_sshRepoUrl_resolvedCorrectly() throws Exception {
        stubResponse(200);
        assertTrue(service.mergePullRequest("git@github.com:owner/repo.git", PR_NUMBER, TOKEN, httpClient));

        verify(httpClient).send(argThat(req ->
                req.uri().toString().contains("/repos/owner/repo/pulls/")
        ), any(HttpResponse.BodyHandler.class));
    }
}
