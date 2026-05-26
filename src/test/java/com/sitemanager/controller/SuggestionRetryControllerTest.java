package com.sitemanager.controller;

import com.sitemanager.model.PlanTask;
import com.sitemanager.model.Suggestion;
import com.sitemanager.model.enums.SuggestionStatus;
import com.sitemanager.model.enums.TaskStatus;
import com.sitemanager.repository.PlanTaskRepository;
import com.sitemanager.repository.SuggestionMessageRepository;
import com.sitemanager.repository.SuggestionRepository;
import com.sitemanager.service.ClaudeService;
import com.sitemanager.service.SlackNotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

import java.util.concurrent.CompletableFuture;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for POST /api/suggestions/{id}/retry.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SuggestionRetryControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SuggestionRepository suggestionRepository;

    @Autowired
    private PlanTaskRepository planTaskRepository;

    @Autowired
    private SuggestionMessageRepository messageRepository;

    @MockBean
    private ClaudeService claudeService;

    @MockBean
    private SlackNotificationService slackNotificationService;

    @BeforeEach
    void setUp() {
        messageRepository.deleteAll();
        planTaskRepository.deleteAll();
        suggestionRepository.deleteAll();

        when(claudeService.generateSessionId()).thenReturn("test-session");
        when(claudeService.executeSingleTask(any(), any(), any(), anyInt(), any(), any(), anyInt(), any(), any(), any()))
                .thenReturn(CompletableFuture.completedFuture("{}"));
        when(claudeService.executePlan(any(), any(), any(), any(), any(), any()))
                .thenReturn(CompletableFuture.completedFuture("{}"));
        when(slackNotificationService.sendNotification(any(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(slackNotificationService.sendApprovalNeededNotification(any()))
                .thenReturn(CompletableFuture.completedFuture(null));
    }

    // -------------------------------------------------------------------------
    // 401 — not logged in
    // -------------------------------------------------------------------------

    @Test
    void retry_unauthenticated_returns401() throws Exception {
        Suggestion s = savedSuggestion(SuggestionStatus.IN_PROGRESS, "Task 1 failed — can retry");

        mockMvc.perform(post("/api/suggestions/{id}/retry", s.getId()))
                .andExpect(status().isUnauthorized());
    }

    // -------------------------------------------------------------------------
    // 400 — wrong state
    // -------------------------------------------------------------------------

    @Test
    void retry_wrongStatus_returns400() throws Exception {
        Suggestion s = savedSuggestion(SuggestionStatus.APPROVED, "Task 1 failed — can retry");
        MockHttpSession session = loggedInSession("alice");

        mockMvc.perform(post("/api/suggestions/{id}/retry", s.getId()).session(session))
                .andExpect(status().isBadRequest());
    }

    @Test
    void retry_inProgressButNoFailedPhase_returns400() throws Exception {
        Suggestion s = savedSuggestion(SuggestionStatus.IN_PROGRESS, "Running task 3");
        MockHttpSession session = loggedInSession("alice");

        mockMvc.perform(post("/api/suggestions/{id}/retry", s.getId()).session(session))
                .andExpect(status().isBadRequest());
    }

    // -------------------------------------------------------------------------
    // 200 — success
    // -------------------------------------------------------------------------

    @Test
    void retry_validFailedState_returns200WithSuggestion() throws Exception {
        Suggestion s = savedSuggestion(SuggestionStatus.IN_PROGRESS, "Task 2 failed — can retry");
        s.setFailureReason("Build failed");
        suggestionRepository.save(s);

        PlanTask t = new PlanTask();
        t.setSuggestionId(s.getId());
        t.setTaskOrder(2);
        t.setTitle("Build step");
        t.setStatus(TaskStatus.FAILED);
        t.setRetryCount(2);
        t.setFailureReason("Build failed");
        planTaskRepository.save(t);

        MockHttpSession session = loggedInSession("alice");

        mockMvc.perform(post("/api/suggestions/{id}/retry", s.getId()).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(s.getId()));
    }

    @Test
    void retry_permanentlyFailedPhase_returns200() throws Exception {
        Suggestion s = savedSuggestion(SuggestionStatus.IN_PROGRESS, "Task 1 permanently failed");
        MockHttpSession session = loggedInSession("alice");

        mockMvc.perform(post("/api/suggestions/{id}/retry", s.getId()).session(session))
                .andExpect(status().isOk());
    }

    // -------------------------------------------------------------------------
    // 403 — ownership enforcement
    // -------------------------------------------------------------------------

    @Test
    void retry_wrongOwner_returns403() throws Exception {
        // Suggestion owned by "alice", but "bob" tries to retry it
        Suggestion s = savedSuggestion(SuggestionStatus.IN_PROGRESS, "Task 2 failed — can retry");
        // savedSuggestion sets authorName to "alice"
        MockHttpSession session = loggedInSession("bob");

        mockMvc.perform(post("/api/suggestions/{id}/retry", s.getId()).session(session))
                .andExpect(status().isForbidden());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private Suggestion savedSuggestion(SuggestionStatus status, String phase) {
        Suggestion s = new Suggestion();
        s.setTitle("Test suggestion");
        s.setDescription("Description");
        s.setStatus(status);
        s.setCurrentPhase(phase);
        s.setAuthorName("alice");
        return suggestionRepository.save(s);
    }

    private MockHttpSession loggedInSession(String username) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("username", username);
        session.setAttribute("userId", 999L);
        session.setAttribute("role", "USER");
        return session;
    }
}
