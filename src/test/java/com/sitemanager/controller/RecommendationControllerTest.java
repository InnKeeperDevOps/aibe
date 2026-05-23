package com.sitemanager.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitemanager.model.RecommendationResult;
import com.sitemanager.model.SiteSettings;
import com.sitemanager.model.Suggestion;
import com.sitemanager.model.enums.RecommendationResultStatus;
import com.sitemanager.model.enums.SuggestionStatus;
import com.sitemanager.model.RecommendationRun;
import com.sitemanager.model.enums.RecommendationRunStatus;
import com.sitemanager.repository.RecommendationResultRepository;
import com.sitemanager.repository.RecommendationRunRepository;
import com.sitemanager.repository.SiteSettingsRepository;
import com.sitemanager.repository.SuggestionRepository;
import com.sitemanager.repository.UserRepository;
import com.sitemanager.service.ClaudeService;
import com.sitemanager.service.SiteSettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.sitemanager.model.enums.Priority;

@SpringBootTest
@AutoConfigureMockMvc
class RecommendationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private SiteSettingsRepository settingsRepository;

    @Autowired
    private SuggestionRepository suggestionRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private SiteSettingsService settingsService;

    @Autowired
    private RecommendationRunRepository runRepository;

    @Autowired
    private RecommendationResultRepository resultRepository;

    @MockBean
    private ClaudeService claudeService;

    private static final String VALID_RESPONSE =
            "[{\"title\":\"Add email notifications\",\"description\":\"Notify users when their suggestions are approved.\"}," +
            "{\"title\":\"Improve search\",\"description\":\"Allow filtering suggestions by keyword.\"}," +
            "{\"title\":\"Add tagging\",\"description\":\"Let users tag suggestions by category.\"}," +
            "{\"title\":\"Bulk actions\",\"description\":\"Allow admins to approve multiple suggestions at once.\"}," +
            "{\"title\":\"Dashboard metrics\",\"description\":\"Show a summary of suggestion activity on the main page.\"}]";

    @BeforeEach
    void setUp() throws Exception {
        resultRepository.deleteAll();
        runRepository.deleteAll();
        suggestionRepository.deleteAll();
        settingsRepository.deleteAll();
        userRepository.deleteAll();
        when(claudeService.getMainRepoDir()).thenReturn("/tmp/test-main-repo");
    }

    private MockHttpSession adminSession() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("role", "ADMIN");
        session.setAttribute("username", "admin");
        session.setAttribute("userId", 1L);
        return session;
    }

    private MockHttpSession rootAdminSession() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("role", "ROOT_ADMIN");
        session.setAttribute("username", "root");
        session.setAttribute("userId", 2L);
        return session;
    }

    private String startTask(MockHttpSession session) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/recommendations")
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.taskId").exists())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("taskId").asText();
    }

    private String pollUntilDone(String taskId, int maxAttempts) throws Exception {
        for (int i = 0; i < maxAttempts; i++) {
            Thread.sleep(200);
            MvcResult poll = mockMvc.perform(get("/api/recommendations/status/" + taskId))
                    .andExpect(status().isOk())
                    .andReturn();
            String body = poll.getResponse().getContentAsString();
            String status = objectMapper.readTree(body).get("status").asText();
            if (!"pending".equals(status)) return body;
        }
        throw new AssertionError("Task did not complete within " + maxAttempts + " polls");
    }

    @Test
    void getRecommendations_withoutSession_returns403() throws Exception {
        mockMvc.perform(post("/api/recommendations")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void getRecommendations_asAdmin_returnsRecommendations() throws Exception {
        when(claudeService.getRecommendations(any())).thenReturn(VALID_RESPONSE);

        String taskId = startTask(adminSession());
        String result = pollUntilDone(taskId, 50);

        var tree = objectMapper.readTree(result);
        assert "done".equals(tree.get("status").asText());
        assert tree.get("data").isArray();
        assert tree.get("data").size() == 5;
        assert "Add email notifications".equals(tree.get("data").get(0).get("title").asText());
    }

    @Test
    void getRecommendations_asRootAdmin_returnsRecommendations() throws Exception {
        when(claudeService.getRecommendations(any())).thenReturn(VALID_RESPONSE);

        String taskId = startTask(rootAdminSession());
        String result = pollUntilDone(taskId, 50);

        var tree = objectMapper.readTree(result);
        assert "done".equals(tree.get("status").asText());
        assert tree.get("data").isArray();
        assert tree.get("data").size() == 5;
    }

    @Test
    void getRecommendations_onTimeout_returnsError() throws Exception {
        when(claudeService.getRecommendations(any())).thenThrow(new RuntimeException("Claude CLI timed out after 30 minutes"));

        String taskId = startTask(adminSession());
        String result = pollUntilDone(taskId, 50);

        var tree = objectMapper.readTree(result);
        assert "error".equals(tree.get("status").asText());
        assert tree.get("error").asText().contains("too long");
    }

    @Test
    void getRecommendations_onMalformedResponse_returnsError() throws Exception {
        when(claudeService.getRecommendations(any())).thenReturn("This is not JSON at all");

        String taskId = startTask(adminSession());
        String result = pollUntilDone(taskId, 50);

        var tree = objectMapper.readTree(result);
        assert "error".equals(tree.get("status").asText());
        assert tree.get("error").asText().contains("unexpected response");
    }

    @Test
    void getRecommendations_onEmptyResponse_returnsError() throws Exception {
        when(claudeService.getRecommendations(any())).thenReturn("");

        String taskId = startTask(adminSession());
        String result = pollUntilDone(taskId, 50);

        var tree = objectMapper.readTree(result);
        assert "error".equals(tree.get("status").asText());
    }

    @Test
    void getRecommendations_onUnexpectedException_returnsError() throws Exception {
        when(claudeService.getRecommendations(any())).thenThrow(new RuntimeException("API error: status 503"));

        String taskId = startTask(adminSession());
        String result = pollUntilDone(taskId, 50);

        var tree = objectMapper.readTree(result);
        assert "error".equals(tree.get("status").asText());
    }

    @Test
    void getStatus_unknownTaskId_returns404() throws Exception {
        mockMvc.perform(get("/api/recommendations/status/nonexistent"))
                .andExpect(status().isNotFound());
    }

    @Test
    void getRecommendations_promptIncludesSiteName() throws Exception {
        SiteSettings settings = settingsService.getSettings();
        settings.setSiteName("My Awesome Site");
        settingsRepository.save(settings);

        when(claudeService.getRecommendations(any())).thenReturn(VALID_RESPONSE);

        String taskId = startTask(adminSession());
        pollUntilDone(taskId, 50);

        org.mockito.Mockito.verify(claudeService).getRecommendations(
                org.mockito.ArgumentMatchers.contains("My Awesome Site"));
    }

    @Test
    void getRecommendations_withPartialValidResponse_returnsValidItems() throws Exception {
        String partialResponse = "[{\"title\":\"Valid title\",\"description\":\"Valid desc\"}," +
                "{\"description\":\"Missing title - should be skipped\"}]";
        when(claudeService.getRecommendations(any())).thenReturn(partialResponse);

        String taskId = startTask(adminSession());
        String result = pollUntilDone(taskId, 50);

        var tree = objectMapper.readTree(result);
        assert "done".equals(tree.get("status").asText());
        assert tree.get("data").size() == 1;
        assert "Valid title".equals(tree.get("data").get(0).get("title").asText());
    }

    @Test
    void buildPrompt_includesAllSiteInfo() {
        RecommendationController controller = new RecommendationController(
                null, null, null, null, objectMapper, null, null);

        SiteSettings settings = new SiteSettings();
        settings.setSiteName("Test Site");
        settings.setTargetRepoUrl("https://github.com/example/repo");
        settings.setAllowAnonymousSuggestions(true);
        settings.setAllowVoting(false);
        settings.setRequireApproval(true);
        settings.setAutoMergePr(false);

        Map<SuggestionStatus, Long> statusCounts = Map.of(
                SuggestionStatus.DRAFT, 3L,
                SuggestionStatus.APPROVED, 2L
        );

        String prompt = controller.buildPrompt(settings, statusCounts, 5, null);

        assert prompt.contains("Test Site");
        assert prompt.contains("https://github.com/example/repo");
        assert prompt.contains("DRAFT");
        assert prompt.contains("APPROVED");
        assert prompt.contains("5");
    }

    @Test
    void buildPrompt_withProjectDefinition_includesDefinitionAndComparisonInstructions() {
        RecommendationController controller = new RecommendationController(
                null, null, null, null, objectMapper, null, null);

        SiteSettings settings = new SiteSettings();
        settings.setSiteName("Test Site");

        String definition = "# Project Definition\n## Overview\nA task management tool.";
        String prompt = controller.buildPrompt(settings, Map.of(), 0, definition);

        assert prompt.contains("PROJECT DEFINITION");
        assert prompt.contains("A task management tool.");
        assert prompt.contains("Compare the PROJECT DEFINITION");
        assert prompt.contains("gaps");
    }

    @Test
    void buildPrompt_withoutProjectDefinition_suggestsCodebaseImprovements() {
        RecommendationController controller = new RecommendationController(
                null, null, null, null, objectMapper, null, null);

        SiteSettings settings = new SiteSettings();
        settings.setSiteName("Test Site");

        String prompt = controller.buildPrompt(settings, Map.of(), 0, null);

        assert !prompt.contains("PROJECT DEFINITION");
        assert prompt.contains("no PROJECT_DEFINITION.md");
        assert prompt.contains("Analyze the codebase");
    }

    @Test
    void parseRecommendations_withValidJson_returnsList() {
        RecommendationController controller = new RecommendationController(
                null, null, null, null, objectMapper, null, null);

        var result = controller.parseRecommendations(VALID_RESPONSE);

        assert result.size() == 5;
        assert result.get(0).get("title").equals("Add email notifications");
        assert result.get(0).get("description").equals("Notify users when their suggestions are approved.");
    }

    @Test
    void parseRecommendations_withPreambleBeforeJson_extractsArray() {
        RecommendationController controller = new RecommendationController(
                null, null, null, null, objectMapper, null, null);

        String responseWithPreamble = "Here are my recommendations:\n" +
                "[{\"title\":\"Improve search\",\"description\":\"Add search functionality.\"}]";

        var result = controller.parseRecommendations(responseWithPreamble);

        assert result.size() == 1;
        assert result.get(0).get("title").equals("Improve search");
    }

    @Test
    void parseRecommendations_withNoJsonArray_throwsIllegalStateException() {
        RecommendationController controller = new RecommendationController(
                null, null, null, null, objectMapper, null, null);

        try {
            controller.parseRecommendations("No JSON here at all.");
            assert false : "Expected IllegalStateException";
        } catch (IllegalStateException e) {
            // expected
        }
    }

    @Test
    void parseRecommendations_withNullInput_throwsIllegalStateException() {
        RecommendationController controller = new RecommendationController(
                null, null, null, null, objectMapper, null, null);

        try {
            controller.parseRecommendations(null);
            assert false : "Expected IllegalStateException";
        } catch (IllegalStateException e) {
            // expected
        }
    }

    @Test
    void getRecommendations_persistsRunAndResults() throws Exception {
        when(claudeService.getRecommendations(any())).thenReturn(VALID_RESPONSE);

        String taskId = startTask(adminSession());
        pollUntilDone(taskId, 50);

        RecommendationRun run = runRepository.findByTaskId(taskId).orElseThrow();
        assert run.getStatus() == RecommendationRunStatus.DONE;
        assert run.getCompletedAt() != null;
        assert run.getCreatedAt() != null;
        assert run.getRequestedByUsername() != null && run.getRequestedByUsername().equals("admin");

        var stored = resultRepository.findByRunIdOrderByResultOrderAsc(run.getId());
        assert stored.size() == 5;
        assert "Add email notifications".equals(stored.get(0).getTitle());
        assert stored.get(0).getResultOrder() == 0;
    }

    @Test
    void getRecommendations_onError_persistsErrorRunWithoutResults() throws Exception {
        when(claudeService.getRecommendations(any())).thenReturn("not JSON");

        String taskId = startTask(adminSession());
        pollUntilDone(taskId, 50);

        RecommendationRun run = runRepository.findByTaskId(taskId).orElseThrow();
        assert run.getStatus() == RecommendationRunStatus.ERROR;
        assert run.getErrorMessage() != null && run.getErrorMessage().contains("unexpected response");
        assert run.getCompletedAt() != null;

        var stored = resultRepository.findByRunIdOrderByResultOrderAsc(run.getId());
        assert stored.isEmpty();
    }

    @Test
    void getStatus_afterCompletion_isStillRetrievable() throws Exception {
        when(claudeService.getRecommendations(any())).thenReturn(VALID_RESPONSE);

        String taskId = startTask(adminSession());
        pollUntilDone(taskId, 50);

        // A second status call should still work (history is not deleted on read).
        MvcResult repeat = mockMvc.perform(get("/api/recommendations/status/" + taskId))
                .andExpect(status().isOk())
                .andReturn();
        var tree = objectMapper.readTree(repeat.getResponse().getContentAsString());
        assert "done".equals(tree.get("status").asText());
        assert tree.get("data").size() == 5;
    }

    @Test
    void listRuns_withoutSession_returns403() throws Exception {
        mockMvc.perform(get("/api/recommendations/runs"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void listRuns_whenEmpty_returnsEmptyArray() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/recommendations/runs").session(adminSession()))
                .andExpect(status().isOk())
                .andReturn();
        var tree = objectMapper.readTree(result.getResponse().getContentAsString());
        assert tree.get("runs").isArray();
        assert tree.get("runs").size() == 0;
    }

    @Test
    void listRuns_returnsRunsNewestFirstWithSummaryFields() throws Exception {
        when(claudeService.getRecommendations(any())).thenReturn(VALID_RESPONSE);

        // Run #1 — completes successfully
        String firstTaskId = startTask(adminSession());
        pollUntilDone(firstTaskId, 50);

        // Tiny gap so timestamps differ for ordering
        Thread.sleep(50);

        // Run #2 — fails to parse
        when(claudeService.getRecommendations(any())).thenReturn("not JSON");
        String secondTaskId = startTask(adminSession());
        pollUntilDone(secondTaskId, 50);

        MvcResult result = mockMvc.perform(get("/api/recommendations/runs").session(adminSession()))
                .andExpect(status().isOk())
                .andReturn();
        var tree = objectMapper.readTree(result.getResponse().getContentAsString());
        var runs = tree.get("runs");
        assert runs.isArray();
        assert runs.size() == 2;

        // Newest first — second run (the failing one) should be the first entry.
        var newest = runs.get(0);
        assert secondTaskId.equals(newest.get("taskId").asText());
        assert "ERROR".equals(newest.get("status").asText());
        assert newest.get("errorMessage").asText().contains("unexpected response");
        assert newest.get("createdAt").asText().length() > 0;
        assert newest.get("completedAt").asText().length() > 0;
        assert "admin".equals(newest.get("requestedByUsername").asText());
        assert newest.get("resultCount").asLong() == 0L;

        var older = runs.get(1);
        assert firstTaskId.equals(older.get("taskId").asText());
        assert "DONE".equals(older.get("status").asText());
        assert older.get("resultCount").asLong() == 5L;
    }

    @Test
    void listRuns_filterByStatus_returnsOnlyMatchingRuns() throws Exception {
        // One DONE run + one ERROR run
        when(claudeService.getRecommendations(any())).thenReturn(VALID_RESPONSE);
        String doneTaskId = startTask(adminSession());
        pollUntilDone(doneTaskId, 50);

        when(claudeService.getRecommendations(any())).thenReturn("not JSON");
        String errorTaskId = startTask(adminSession());
        pollUntilDone(errorTaskId, 50);

        MvcResult result = mockMvc.perform(get("/api/recommendations/runs")
                        .param("status", "DONE")
                        .session(adminSession()))
                .andExpect(status().isOk())
                .andReturn();
        var tree = objectMapper.readTree(result.getResponse().getContentAsString());
        var runs = tree.get("runs");
        assert runs.size() == 1;
        assert doneTaskId.equals(runs.get(0).get("taskId").asText());
        assert "DONE".equals(runs.get(0).get("status").asText());
    }

    @Test
    void listRuns_filterByStatusLowercase_isAcceptedCaseInsensitively() throws Exception {
        when(claudeService.getRecommendations(any())).thenReturn("not JSON");
        String taskId = startTask(adminSession());
        pollUntilDone(taskId, 50);

        MvcResult result = mockMvc.perform(get("/api/recommendations/runs")
                        .param("status", "error")
                        .session(adminSession()))
                .andExpect(status().isOk())
                .andReturn();
        var tree = objectMapper.readTree(result.getResponse().getContentAsString());
        assert tree.get("runs").size() == 1;
    }

    @Test
    void listRuns_filterByUnknownStatus_returns400() throws Exception {
        mockMvc.perform(get("/api/recommendations/runs")
                        .param("status", "BOGUS")
                        .session(adminSession()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void listRuns_filterByDateRange_includesOnlyRunsInWindow() throws Exception {
        // Seed runs at specific timestamps so the date filter can be exercised
        // deterministically without depending on real-time clock ordering.
        RecommendationRun jan = new RecommendationRun();
        jan.setTaskId("jan-task");
        jan.setStatus(RecommendationRunStatus.DONE);
        jan.setCreatedAt(java.time.Instant.parse("2026-01-10T00:00:00Z"));
        jan.setUpdatedAt(java.time.Instant.parse("2026-01-10T00:00:00Z"));
        runRepository.save(jan);

        RecommendationRun feb = new RecommendationRun();
        feb.setTaskId("feb-task");
        feb.setStatus(RecommendationRunStatus.DONE);
        feb.setCreatedAt(java.time.Instant.parse("2026-02-10T00:00:00Z"));
        feb.setUpdatedAt(java.time.Instant.parse("2026-02-10T00:00:00Z"));
        runRepository.save(feb);

        RecommendationRun apr = new RecommendationRun();
        apr.setTaskId("apr-task");
        apr.setStatus(RecommendationRunStatus.DONE);
        apr.setCreatedAt(java.time.Instant.parse("2026-04-10T00:00:00Z"));
        apr.setUpdatedAt(java.time.Instant.parse("2026-04-10T00:00:00Z"));
        runRepository.save(apr);

        MvcResult result = mockMvc.perform(get("/api/recommendations/runs")
                        .param("from", "2026-02-01T00:00:00Z")
                        .param("to", "2026-03-01T00:00:00Z")
                        .session(adminSession()))
                .andExpect(status().isOk())
                .andReturn();
        var tree = objectMapper.readTree(result.getResponse().getContentAsString());
        var runs = tree.get("runs");
        assert runs.size() == 1;
        assert "feb-task".equals(runs.get(0).get("taskId").asText());
    }

    @Test
    void listRuns_filterByFromOnly_returnsRunsAtOrAfterFrom() throws Exception {
        RecommendationRun a = new RecommendationRun();
        a.setTaskId("a");
        a.setStatus(RecommendationRunStatus.DONE);
        a.setCreatedAt(java.time.Instant.parse("2026-01-01T00:00:00Z"));
        a.setUpdatedAt(java.time.Instant.parse("2026-01-01T00:00:00Z"));
        runRepository.save(a);

        RecommendationRun b = new RecommendationRun();
        b.setTaskId("b");
        b.setStatus(RecommendationRunStatus.DONE);
        b.setCreatedAt(java.time.Instant.parse("2026-05-01T00:00:00Z"));
        b.setUpdatedAt(java.time.Instant.parse("2026-05-01T00:00:00Z"));
        runRepository.save(b);

        MvcResult result = mockMvc.perform(get("/api/recommendations/runs")
                        .param("from", "2026-03-01T00:00:00Z")
                        .session(adminSession()))
                .andExpect(status().isOk())
                .andReturn();
        var tree = objectMapper.readTree(result.getResponse().getContentAsString());
        assert tree.get("runs").size() == 1;
        assert "b".equals(tree.get("runs").get(0).get("taskId").asText());
    }

    @Test
    void listRuns_filterByToOnly_returnsRunsAtOrBeforeTo() throws Exception {
        RecommendationRun a = new RecommendationRun();
        a.setTaskId("early");
        a.setStatus(RecommendationRunStatus.DONE);
        a.setCreatedAt(java.time.Instant.parse("2026-01-01T00:00:00Z"));
        a.setUpdatedAt(java.time.Instant.parse("2026-01-01T00:00:00Z"));
        runRepository.save(a);

        RecommendationRun b = new RecommendationRun();
        b.setTaskId("late");
        b.setStatus(RecommendationRunStatus.DONE);
        b.setCreatedAt(java.time.Instant.parse("2026-05-01T00:00:00Z"));
        b.setUpdatedAt(java.time.Instant.parse("2026-05-01T00:00:00Z"));
        runRepository.save(b);

        MvcResult result = mockMvc.perform(get("/api/recommendations/runs")
                        .param("to", "2026-03-01T00:00:00Z")
                        .session(adminSession()))
                .andExpect(status().isOk())
                .andReturn();
        var tree = objectMapper.readTree(result.getResponse().getContentAsString());
        assert tree.get("runs").size() == 1;
        assert "early".equals(tree.get("runs").get(0).get("taskId").asText());
    }

    @Test
    void listRuns_filterByInvalidToDate_returns400WithFieldName() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/recommendations/runs")
                        .param("to", "garbage")
                        .session(adminSession()))
                .andExpect(status().isBadRequest())
                .andReturn();
        var tree = objectMapper.readTree(result.getResponse().getContentAsString());
        // The error mentions the field name so the UI can highlight the right input.
        assert tree.get("error").asText().contains("to");
    }

    @Test
    void listRuns_filterByStatusWithNoMatches_returnsEmptyArray() throws Exception {
        when(claudeService.getRecommendations(any())).thenReturn(VALID_RESPONSE);
        String taskId = startTask(adminSession());
        pollUntilDone(taskId, 50);

        MvcResult result = mockMvc.perform(get("/api/recommendations/runs")
                        .param("status", "ERROR")
                        .session(adminSession()))
                .andExpect(status().isOk())
                .andReturn();
        var tree = objectMapper.readTree(result.getResponse().getContentAsString());
        assert tree.get("runs").isArray();
        assert tree.get("runs").size() == 0;
    }

    @Test
    void listRuns_filterByInvalidDate_returns400() throws Exception {
        mockMvc.perform(get("/api/recommendations/runs")
                        .param("from", "not-a-date")
                        .session(adminSession()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void listRuns_combineStatusAndDate_intersectsBothFilters() throws Exception {
        RecommendationRun doneJan = new RecommendationRun();
        doneJan.setTaskId("done-jan");
        doneJan.setStatus(RecommendationRunStatus.DONE);
        doneJan.setCreatedAt(java.time.Instant.parse("2026-01-10T00:00:00Z"));
        doneJan.setUpdatedAt(java.time.Instant.parse("2026-01-10T00:00:00Z"));
        runRepository.save(doneJan);

        RecommendationRun errJan = new RecommendationRun();
        errJan.setTaskId("err-jan");
        errJan.setStatus(RecommendationRunStatus.ERROR);
        errJan.setCreatedAt(java.time.Instant.parse("2026-01-15T00:00:00Z"));
        errJan.setUpdatedAt(java.time.Instant.parse("2026-01-15T00:00:00Z"));
        runRepository.save(errJan);

        RecommendationRun doneMar = new RecommendationRun();
        doneMar.setTaskId("done-mar");
        doneMar.setStatus(RecommendationRunStatus.DONE);
        doneMar.setCreatedAt(java.time.Instant.parse("2026-03-10T00:00:00Z"));
        doneMar.setUpdatedAt(java.time.Instant.parse("2026-03-10T00:00:00Z"));
        runRepository.save(doneMar);

        MvcResult result = mockMvc.perform(get("/api/recommendations/runs")
                        .param("status", "DONE")
                        .param("from", "2026-01-01T00:00:00Z")
                        .param("to", "2026-01-31T23:59:59Z")
                        .session(adminSession()))
                .andExpect(status().isOk())
                .andReturn();
        var tree = objectMapper.readTree(result.getResponse().getContentAsString());
        var runs = tree.get("runs");
        assert runs.size() == 1;
        assert "done-jan".equals(runs.get(0).get("taskId").asText());
    }

    @Test
    void listRuns_emptyFilterParams_returnsAllRuns() throws Exception {
        when(claudeService.getRecommendations(any())).thenReturn(VALID_RESPONSE);
        String taskId = startTask(adminSession());
        pollUntilDone(taskId, 50);

        // Blank status/from/to should be treated as "no filter"
        MvcResult result = mockMvc.perform(get("/api/recommendations/runs")
                        .param("status", "")
                        .param("from", "")
                        .param("to", "")
                        .session(adminSession()))
                .andExpect(status().isOk())
                .andReturn();
        var tree = objectMapper.readTree(result.getResponse().getContentAsString());
        assert tree.get("runs").size() == 1;
    }

    @Test
    void parseStatus_handlesNullBlankAndCase() {
        assert RecommendationController.parseStatus(null) == null;
        assert RecommendationController.parseStatus("") == null;
        assert RecommendationController.parseStatus("  ") == null;
        assert RecommendationController.parseStatus("done") == RecommendationRunStatus.DONE;
        assert RecommendationController.parseStatus("ERROR") == RecommendationRunStatus.ERROR;
        try {
            RecommendationController.parseStatus("nope");
            assert false : "Expected IllegalArgumentException";
        } catch (IllegalArgumentException e) {
            // expected
        }
    }

    @Test
    void parseInstant_handlesNullBlankValidAndInvalid() {
        assert RecommendationController.parseInstant(null, "from") == null;
        assert RecommendationController.parseInstant("", "from") == null;
        assert RecommendationController.parseInstant("  ", "from") == null;
        assert java.time.Instant.parse("2026-01-01T00:00:00Z")
                .equals(RecommendationController.parseInstant("2026-01-01T00:00:00Z", "from"));
        try {
            RecommendationController.parseInstant("garbage", "to");
            assert false : "Expected IllegalArgumentException";
        } catch (IllegalArgumentException e) {
            assert e.getMessage().contains("to");
        }
    }

    @Test
    void getRunDetail_withoutSession_returns403() throws Exception {
        mockMvc.perform(get("/api/recommendations/runs/anything"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void getRunDetail_unknownRun_returns404() throws Exception {
        mockMvc.perform(get("/api/recommendations/runs/does-not-exist").session(adminSession()))
                .andExpect(status().isNotFound());
    }

    @Test
    void getRunDetail_returnsRunAndRecommendations() throws Exception {
        when(claudeService.getRecommendations(any())).thenReturn(VALID_RESPONSE);

        String taskId = startTask(adminSession());
        pollUntilDone(taskId, 50);

        MvcResult result = mockMvc.perform(get("/api/recommendations/runs/" + taskId).session(adminSession()))
                .andExpect(status().isOk())
                .andReturn();
        var tree = objectMapper.readTree(result.getResponse().getContentAsString());
        assert taskId.equals(tree.get("taskId").asText());
        assert "DONE".equals(tree.get("status").asText());
        assert tree.get("createdAt").asText().length() > 0;
        assert tree.get("completedAt").asText().length() > 0;
        assert "admin".equals(tree.get("requestedByUsername").asText());
        assert tree.get("resultCount").asLong() == 5L;

        var recs = tree.get("recommendations");
        assert recs.isArray();
        assert recs.size() == 5;
        assert "Add email notifications".equals(recs.get(0).get("title").asText());
        assert recs.get(0).get("description").asText().length() > 0;
    }

    @Test
    void rerunRun_withoutSession_returns403() throws Exception {
        mockMvc.perform(post("/api/recommendations/runs/anything/rerun")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void rerunRun_unknownTaskId_returns404() throws Exception {
        mockMvc.perform(post("/api/recommendations/runs/does-not-exist/rerun")
                        .session(adminSession())
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound());
    }

    @Test
    void rerunRun_validTaskId_createsNewRunWithDifferentTaskId() throws Exception {
        when(claudeService.getRecommendations(any())).thenReturn(VALID_RESPONSE);

        String originalTaskId = startTask(adminSession());
        pollUntilDone(originalTaskId, 50);

        MvcResult result = mockMvc.perform(post("/api/recommendations/runs/" + originalTaskId + "/rerun")
                        .session(adminSession())
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.taskId").exists())
                .andReturn();

        String newTaskId = objectMapper.readTree(result.getResponse().getContentAsString())
                .get("taskId").asText();

        // The rerun must produce a brand-new task ID — it is not a mutation of the original run.
        assert !newTaskId.equals(originalTaskId);

        // Both runs exist independently, and the original is untouched.
        RecommendationRun original = runRepository.findByTaskId(originalTaskId).orElseThrow();
        RecommendationRun fresh = runRepository.findByTaskId(newTaskId).orElseThrow();
        assert original.getStatus() == RecommendationRunStatus.DONE;
        assert fresh.getId() != null && !fresh.getId().equals(original.getId());

        pollUntilDone(newTaskId, 50);
        RecommendationRun finished = runRepository.findByTaskId(newTaskId).orElseThrow();
        assert finished.getStatus() == RecommendationRunStatus.DONE;
        var freshResults = resultRepository.findByRunIdOrderByResultOrderAsc(finished.getId());
        assert freshResults.size() == 5;

        // The original run's results must not have been touched by the rerun.
        var originalResults = resultRepository.findByRunIdOrderByResultOrderAsc(original.getId());
        assert originalResults.size() == 5;
    }

    @Test
    void rerunRun_attributesNewRunToCurrentUser_notOriginalRequester() throws Exception {
        when(claudeService.getRecommendations(any())).thenReturn(VALID_RESPONSE);

        String originalTaskId = startTask(adminSession()); // "admin"
        pollUntilDone(originalTaskId, 50);

        MvcResult result = mockMvc.perform(post("/api/recommendations/runs/" + originalTaskId + "/rerun")
                        .session(rootAdminSession()) // "root" re-runs admin's run
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isAccepted())
                .andReturn();
        String newTaskId = objectMapper.readTree(result.getResponse().getContentAsString())
                .get("taskId").asText();

        RecommendationRun fresh = runRepository.findByTaskId(newTaskId).orElseThrow();
        assert "root".equals(fresh.getRequestedByUsername())
                : "Re-run should be attributed to whoever clicks the button";
    }

    @Test
    void rerunRun_forFailedOriginal_stillCreatesNewRun() throws Exception {
        // Original run fails to parse, but it must still be possible to re-run it.
        when(claudeService.getRecommendations(any())).thenReturn("not JSON");
        String originalTaskId = startTask(adminSession());
        pollUntilDone(originalTaskId, 50);
        RecommendationRun failed = runRepository.findByTaskId(originalTaskId).orElseThrow();
        assert failed.getStatus() == RecommendationRunStatus.ERROR;

        // Now make the rerun succeed.
        when(claudeService.getRecommendations(any())).thenReturn(VALID_RESPONSE);

        MvcResult result = mockMvc.perform(post("/api/recommendations/runs/" + originalTaskId + "/rerun")
                        .session(adminSession())
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isAccepted())
                .andReturn();
        String newTaskId = objectMapper.readTree(result.getResponse().getContentAsString())
                .get("taskId").asText();

        pollUntilDone(newTaskId, 50);
        RecommendationRun fresh = runRepository.findByTaskId(newTaskId).orElseThrow();
        assert fresh.getStatus() == RecommendationRunStatus.DONE;

        // The original failed run still exists in history, unchanged.
        RecommendationRun stillFailed = runRepository.findByTaskId(originalTaskId).orElseThrow();
        assert stillFailed.getStatus() == RecommendationRunStatus.ERROR;
    }

    @Test
    void getRunDetail_forFailedRun_returnsStatusAndErrorButNoRecommendations() throws Exception {
        when(claudeService.getRecommendations(any())).thenReturn("not JSON");

        String taskId = startTask(adminSession());
        pollUntilDone(taskId, 50);

        MvcResult result = mockMvc.perform(get("/api/recommendations/runs/" + taskId).session(adminSession()))
                .andExpect(status().isOk())
                .andReturn();
        var tree = objectMapper.readTree(result.getResponse().getContentAsString());
        assert "ERROR".equals(tree.get("status").asText());
        assert tree.get("errorMessage").asText().contains("unexpected response");
        assert tree.get("recommendations").isArray();
        assert tree.get("recommendations").size() == 0;
        assert tree.get("resultCount").asLong() == 0L;
    }

    // -------------------------------------------------------------------------
    // ACTED_ON behaviour: once a recommendation has been turned into a tracked
    // suggestion, it disappears from the active list but stays in history.
    // -------------------------------------------------------------------------

    private Long createTrackedSuggestion(String title) {
        Suggestion s = new Suggestion();
        s.setTitle(title);
        s.setDescription("Body for " + title);
        s.setStatus(SuggestionStatus.DISCUSSING);
        s.setAuthorName("admin");
        s.setPriority(Priority.MEDIUM);
        return suggestionRepository.save(s).getId();
    }

    private Long firstResultId(String taskId) {
        RecommendationRun run = runRepository.findByTaskId(taskId).orElseThrow();
        var rows = resultRepository.findByRunIdOrderByResultOrderAsc(run.getId());
        assert !rows.isEmpty();
        return rows.get(0).getId();
    }

    @Test
    void getStatus_includesResultId_soFrontendCanMarkActedOn() throws Exception {
        when(claudeService.getRecommendations(any())).thenReturn(VALID_RESPONSE);

        String taskId = startTask(adminSession());
        String body = pollUntilDone(taskId, 50);

        var tree = objectMapper.readTree(body);
        assert "done".equals(tree.get("status").asText());
        var data = tree.get("data");
        assert data.size() == 5;
        // Each active item must carry the result id so the UI can later POST
        // to the act-on endpoint when the user creates a suggestion from it.
        for (int i = 0; i < data.size(); i++) {
            assert data.get(i).get("id").asLong() > 0L
                    : "active recommendation #" + i + " is missing an id";
        }
    }

    @Test
    void markResultActedOn_withoutSession_returns403() throws Exception {
        mockMvc.perform(post("/api/recommendations/results/1/act-on")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"suggestionId\":1}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void markResultActedOn_unknownResult_returns404() throws Exception {
        Long suggestionId = createTrackedSuggestion("Some suggestion");
        mockMvc.perform(post("/api/recommendations/results/9999999/act-on")
                        .session(adminSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"suggestionId\":" + suggestionId + "}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void markResultActedOn_missingSuggestionId_returns400() throws Exception {
        when(claudeService.getRecommendations(any())).thenReturn(VALID_RESPONSE);
        String taskId = startTask(adminSession());
        pollUntilDone(taskId, 50);
        Long resultId = firstResultId(taskId);

        mockMvc.perform(post("/api/recommendations/results/" + resultId + "/act-on")
                        .session(adminSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void markResultActedOn_unknownSuggestion_returns400() throws Exception {
        when(claudeService.getRecommendations(any())).thenReturn(VALID_RESPONSE);
        String taskId = startTask(adminSession());
        pollUntilDone(taskId, 50);
        Long resultId = firstResultId(taskId);

        mockMvc.perform(post("/api/recommendations/results/" + resultId + "/act-on")
                        .session(adminSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"suggestionId\":9999999}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void markResultActedOn_flipsStatusAndLinksSuggestion() throws Exception {
        when(claudeService.getRecommendations(any())).thenReturn(VALID_RESPONSE);
        String taskId = startTask(adminSession());
        pollUntilDone(taskId, 50);
        Long resultId = firstResultId(taskId);
        Long suggestionId = createTrackedSuggestion("Email notifications");

        MvcResult response = mockMvc.perform(post("/api/recommendations/results/" + resultId + "/act-on")
                        .session(adminSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"suggestionId\":" + suggestionId + "}"))
                .andExpect(status().isOk())
                .andReturn();
        var tree = objectMapper.readTree(response.getResponse().getContentAsString());
        assert "ACTED_ON".equals(tree.get("status").asText());
        assert tree.get("actedOnSuggestionId").asLong() == suggestionId;

        RecommendationResult stored = resultRepository.findById(resultId).orElseThrow();
        assert stored.getStatus() == RecommendationResultStatus.ACTED_ON;
        assert suggestionId.equals(stored.getActedOnSuggestionId());
    }

    @Test
    void markResultActedOn_isIdempotent_canRemarkWithDifferentSuggestion() throws Exception {
        when(claudeService.getRecommendations(any())).thenReturn(VALID_RESPONSE);
        String taskId = startTask(adminSession());
        pollUntilDone(taskId, 50);
        Long resultId = firstResultId(taskId);
        Long firstSuggestionId = createTrackedSuggestion("First");
        Long secondSuggestionId = createTrackedSuggestion("Second");

        mockMvc.perform(post("/api/recommendations/results/" + resultId + "/act-on")
                        .session(adminSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"suggestionId\":" + firstSuggestionId + "}"))
                .andExpect(status().isOk());

        // Re-marking with a different suggestion id should not error and
        // should update the linked id (the row is never created or deleted —
        // only flipped/updated in place).
        MvcResult response = mockMvc.perform(post("/api/recommendations/results/" + resultId + "/act-on")
                        .session(adminSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"suggestionId\":" + secondSuggestionId + "}"))
                .andExpect(status().isOk())
                .andReturn();
        var tree = objectMapper.readTree(response.getResponse().getContentAsString());
        assert tree.get("actedOnSuggestionId").asLong() == secondSuggestionId;

        RecommendationResult stored = resultRepository.findById(resultId).orElseThrow();
        assert secondSuggestionId.equals(stored.getActedOnSuggestionId());
    }

    @Test
    void getStatus_excludesActedOnResults_fromActiveList() throws Exception {
        when(claudeService.getRecommendations(any())).thenReturn(VALID_RESPONSE);
        String taskId = startTask(adminSession());
        pollUntilDone(taskId, 50);

        // Pick the first recommendation, mark it ACTED_ON.
        Long firstId = firstResultId(taskId);
        Long suggestionId = createTrackedSuggestion("Linked");
        mockMvc.perform(post("/api/recommendations/results/" + firstId + "/act-on")
                        .session(adminSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"suggestionId\":" + suggestionId + "}"))
                .andExpect(status().isOk());

        // Active list should now have one fewer entry, and the acted-on
        // bullet must not appear in it.
        MvcResult result = mockMvc.perform(get("/api/recommendations/status/" + taskId))
                .andExpect(status().isOk())
                .andReturn();
        var tree = objectMapper.readTree(result.getResponse().getContentAsString());
        assert "done".equals(tree.get("status").asText());
        var data = tree.get("data");
        assert data.size() == 4 : "expected 4 active recommendations, got " + data.size();
        for (int i = 0; i < data.size(); i++) {
            assert data.get(i).get("id").asLong() != firstId
                    : "acted-on recommendation must not appear in the active list";
        }
    }

    @Test
    void getStatus_whenAllActedOn_returnsEmptyActiveList() throws Exception {
        when(claudeService.getRecommendations(any())).thenReturn(VALID_RESPONSE);
        String taskId = startTask(adminSession());
        pollUntilDone(taskId, 50);

        RecommendationRun run = runRepository.findByTaskId(taskId).orElseThrow();
        var rows = resultRepository.findByRunIdOrderByResultOrderAsc(run.getId());
        Long suggestionId = createTrackedSuggestion("Bulk linked");
        for (RecommendationResult r : rows) {
            mockMvc.perform(post("/api/recommendations/results/" + r.getId() + "/act-on")
                            .session(adminSession())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"suggestionId\":" + suggestionId + "}"))
                    .andExpect(status().isOk());
        }

        MvcResult result = mockMvc.perform(get("/api/recommendations/status/" + taskId))
                .andExpect(status().isOk())
                .andReturn();
        var tree = objectMapper.readTree(result.getResponse().getContentAsString());
        assert "done".equals(tree.get("status").asText());
        assert tree.get("data").size() == 0;
    }

    @Test
    void getRunDetail_includesActedOnResults_withStatusAndLinkedSuggestionId() throws Exception {
        // History must remain complete and browsable even after the active
        // list has dropped the items.
        when(claudeService.getRecommendations(any())).thenReturn(VALID_RESPONSE);
        String taskId = startTask(adminSession());
        pollUntilDone(taskId, 50);

        Long firstId = firstResultId(taskId);
        Long suggestionId = createTrackedSuggestion("Visible in history");
        mockMvc.perform(post("/api/recommendations/results/" + firstId + "/act-on")
                        .session(adminSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"suggestionId\":" + suggestionId + "}"))
                .andExpect(status().isOk());

        MvcResult result = mockMvc.perform(get("/api/recommendations/runs/" + taskId).session(adminSession()))
                .andExpect(status().isOk())
                .andReturn();
        var tree = objectMapper.readTree(result.getResponse().getContentAsString());
        var recs = tree.get("recommendations");
        // All 5 bullets must still be visible in the history detail — the
        // acted-on one is just marked, never removed.
        assert recs.size() == 5;

        boolean foundActedOn = false;
        boolean foundPending = false;
        for (int i = 0; i < recs.size(); i++) {
            var rec = recs.get(i);
            assert rec.has("id");
            assert rec.has("status");
            if (rec.get("id").asLong() == firstId) {
                foundActedOn = true;
                assert "ACTED_ON".equals(rec.get("status").asText());
                assert rec.get("actedOnSuggestionId").asLong() == suggestionId;
            } else {
                if ("PENDING".equals(rec.get("status").asText())) foundPending = true;
            }
        }
        assert foundActedOn : "history did not include the acted-on bullet";
        assert foundPending : "history should still show the remaining pending bullets";

        // Result count in the run summary stays at 5 — the row was not deleted.
        assert tree.get("resultCount").asLong() == 5L;
    }

    @Test
    void markResultActedOn_doesNotAffectOtherResults() throws Exception {
        when(claudeService.getRecommendations(any())).thenReturn(VALID_RESPONSE);
        String taskId = startTask(adminSession());
        pollUntilDone(taskId, 50);

        RecommendationRun run = runRepository.findByTaskId(taskId).orElseThrow();
        var before = resultRepository.findByRunIdOrderByResultOrderAsc(run.getId());
        assert before.size() == 5;

        Long firstId = before.get(0).getId();
        Long suggestionId = createTrackedSuggestion("Single mark");
        mockMvc.perform(post("/api/recommendations/results/" + firstId + "/act-on")
                        .session(adminSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"suggestionId\":" + suggestionId + "}"))
                .andExpect(status().isOk());

        var after = resultRepository.findByRunIdOrderByResultOrderAsc(run.getId());
        assert after.size() == 5;
        for (RecommendationResult r : after) {
            if (r.getId().equals(firstId)) {
                assert r.getStatus() == RecommendationResultStatus.ACTED_ON;
            } else {
                assert r.getStatus() == RecommendationResultStatus.PENDING
                        : "sibling recommendation " + r.getId() + " was unexpectedly marked acted-on";
                assert r.getActedOnSuggestionId() == null;
            }
        }
    }
}
