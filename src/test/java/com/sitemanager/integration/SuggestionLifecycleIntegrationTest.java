package com.sitemanager.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitemanager.model.SiteSettings;
import com.sitemanager.model.Suggestion;
import com.sitemanager.model.SuggestionMessage;
import com.sitemanager.model.User;
import com.sitemanager.model.UserGroup;
import com.sitemanager.model.enums.SenderType;
import com.sitemanager.model.enums.SuggestionStatus;
import com.sitemanager.model.enums.UserRole;
import com.sitemanager.repository.PlanTaskRepository;
import com.sitemanager.repository.SiteSettingsRepository;
import com.sitemanager.repository.SuggestionMessageRepository;
import com.sitemanager.repository.SuggestionRepository;
import com.sitemanager.repository.UserGroupRepository;
import com.sitemanager.repository.UserRepository;
import com.sitemanager.repository.VoteRepository;
import com.sitemanager.service.ClaudeService;
import com.sitemanager.service.SlackNotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.util.concurrent.CompletableFuture;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import java.util.Map;

import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class SuggestionLifecycleIntegrationTest {

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected SuggestionRepository suggestionRepository;

    @Autowired
    protected PlanTaskRepository planTaskRepository;

    @Autowired
    protected SuggestionMessageRepository suggestionMessageRepository;

    @Autowired
    protected SiteSettingsRepository siteSettingsRepository;

    @Autowired
    protected UserRepository userRepository;

    @Autowired
    protected VoteRepository voteRepository;

    @Autowired
    protected UserGroupRepository userGroupRepository;

    @Autowired
    protected PasswordEncoder passwordEncoder;

    @MockBean
    protected ClaudeService claudeService;

    @MockBean
    protected SlackNotificationService slackNotificationService;

    protected User adminUser;
    protected User regularUser;

    @BeforeEach
    void setUp() {
        // Delete in dependency order: child tables first, then parent tables
        suggestionMessageRepository.deleteAll();
        planTaskRepository.deleteAll();
        voteRepository.deleteAll();
        suggestionRepository.deleteAll();
        siteSettingsRepository.deleteAll();
        userRepository.deleteAll();
        userGroupRepository.deleteAll();

        // Create admin user (approved, role=ADMIN)
        adminUser = new User("admin", passwordEncoder.encode("adminpass"), UserRole.ADMIN);
        adminUser.setApproved(true);
        adminUser = userRepository.save(adminUser);

        // Create a group granting regular users the ability to create suggestions
        UserGroup userGroup = new UserGroup("Default", true, true, true, false, false, false);
        userGroup = userGroupRepository.save(userGroup);

        // Create regular user (approved, role=USER) with group permissions
        regularUser = new User("testuser", passwordEncoder.encode("userpass"), UserRole.USER);
        regularUser.setApproved(true);
        regularUser.setGroup(userGroup);
        regularUser = userRepository.save(regularUser);

        // Initialize SiteSettings
        SiteSettings settings = new SiteSettings();
        settings.setRequireApproval(true);
        settings.setAutoMergePr(false);
        settings.setAllowAnonymousSuggestions(false);
        siteSettingsRepository.save(settings);

        // Configure default claudeService stubs for async methods
        // All async methods take a Consumer<String> progress callback as the last argument
        when(claudeService.generateSessionId()).thenReturn("test-session-id");
        when(claudeService.getMainRepoDir()).thenReturn("/tmp/test-repo");
        when(claudeService.evaluateSuggestion(any(), any(), any(), any(), any(), any()))
                .thenReturn(CompletableFuture.completedFuture("Evaluation complete"));
        when(claudeService.continueConversation(any(), any(), any(), any(), any()))
                .thenReturn(CompletableFuture.completedFuture("Conversation continued"));
        when(claudeService.executePlan(any(), any(), any(), any(), any(), any()))
                .thenReturn(CompletableFuture.completedFuture("Plan executed"));
        when(claudeService.mergeWithMain(any(), any(), any(), any(), any()))
                .thenReturn(CompletableFuture.completedFuture("Merged"));
        when(slackNotificationService.sendNotification(any(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(slackNotificationService.sendApprovalNeededNotification(any()))
                .thenReturn(CompletableFuture.completedFuture(null));
    }

    protected MockHttpSession adminSession() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("username", adminUser.getUsername());
        session.setAttribute("role", adminUser.getRole().name());
        session.setAttribute("userId", adminUser.getId());
        return session;
    }

    protected MockHttpSession userSession() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("username", regularUser.getUsername());
        session.setAttribute("role", regularUser.getRole().name());
        session.setAttribute("userId", regularUser.getId());
        return session;
    }

    /**
     * Directly sets a suggestion's status to the target value and persists it.
     * Since ClaudeService is mocked, there are no async side effects — callers
     * use this to skip intermediate lifecycle steps cleanly.
     */
    protected void advanceTo(long suggestionId, SuggestionStatus status) {
        Suggestion suggestion = suggestionRepository.findById(suggestionId)
                .orElseThrow(() -> new IllegalArgumentException("Suggestion not found: " + suggestionId));
        suggestion.setStatus(status);
        suggestionRepository.save(suggestion);
    }

    // --- Infrastructure self-tests ---

    @Test
    void setUp_createsTwoUsers() {
        assertThat(userRepository.findAll()).hasSize(2);
    }

    @Test
    void setUp_adminUserHasCorrectRole() {
        assertThat(adminUser.getRole()).isEqualTo(UserRole.ADMIN);
        assertThat(adminUser.isApproved()).isTrue();
    }

    @Test
    void setUp_regularUserHasCorrectRole() {
        assertThat(regularUser.getRole()).isEqualTo(UserRole.USER);
        assertThat(regularUser.isApproved()).isTrue();
    }

    @Test
    void setUp_siteSettingsConfiguredCorrectly() {
        SiteSettings settings = siteSettingsRepository.findAll().get(0);
        assertThat(settings.isRequireApproval()).isTrue();
        assertThat(settings.isAutoMergePr()).isFalse();
        assertThat(settings.isAllowAnonymousSuggestions()).isFalse();
    }

    @Test
    void adminSession_containsCorrectAttributes() {
        MockHttpSession session = adminSession();
        assertThat(session.getAttribute("username")).isEqualTo(adminUser.getUsername());
        assertThat(session.getAttribute("role")).isEqualTo("ADMIN");
        assertThat(session.getAttribute("userId")).isEqualTo(adminUser.getId());
    }

    @Test
    void userSession_containsCorrectAttributes() {
        MockHttpSession session = userSession();
        assertThat(session.getAttribute("username")).isEqualTo(regularUser.getUsername());
        assertThat(session.getAttribute("role")).isEqualTo("USER");
        assertThat(session.getAttribute("userId")).isEqualTo(regularUser.getId());
    }

    @Test
    void advanceTo_updatesStatusInDatabase() {
        Suggestion suggestion = new Suggestion();
        suggestion.setTitle("Test");
        suggestion.setDescription("Test description");
        suggestion.setAuthorName("testuser");
        suggestion.setStatus(SuggestionStatus.DRAFT);
        suggestion = suggestionRepository.save(suggestion);

        advanceTo(suggestion.getId(), SuggestionStatus.DISCUSSING);

        Suggestion updated = suggestionRepository.findById(suggestion.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(SuggestionStatus.DISCUSSING);
    }

    // --- Happy Path Lifecycle ---

    @Test
    void happyPathLifecycle() throws Exception {
        // Phase 1: Create a new DRAFT suggestion
        String createBody = "{\"title\":\"Test suggestion\",\"description\":\"A detailed description\",\"isDraft\":true}";
        String createResponse = mockMvc.perform(post("/api/suggestions")
                        .session(userSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody))
                .andExpect(status().is2xxSuccessful())
                .andReturn().getResponse().getContentAsString();

        long id = objectMapper.readTree(createResponse).get("id").asLong();

        // Phase 2: Verify the suggestion starts as a DRAFT
        mockMvc.perform(get("/api/suggestions/{id}", id)
                        .session(userSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFT"));

        // Phase 3: Edit the draft with updated title and description
        String patchBody = "{\"title\":\"Updated test suggestion\",\"description\":\"An updated detailed description\"}";
        mockMvc.perform(patch("/api/suggestions/{id}/draft", id)
                        .session(userSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(patchBody))
                .andExpect(status().isOk());

        // Phase 4: Configure the AI evaluation stub to transition the suggestion to DISCUSSING
        // and add a clarifying question message, simulating what the AI does asynchronously.
        doAnswer(invocation -> {
            Suggestion s = suggestionRepository.findById(id).orElseThrow();
            s.setStatus(SuggestionStatus.DISCUSSING);
            suggestionRepository.save(s);

            SuggestionMessage msg = new SuggestionMessage(
                    id, SenderType.AI, "Claude", "Please clarify X");
            suggestionMessageRepository.save(msg);

            return CompletableFuture.completedFuture("Evaluation complete");
        }).when(claudeService).evaluateSuggestion(any(), any(), any(), any(), any(), any());

        // Phase 5: Submit the draft for AI evaluation
        mockMvc.perform(post("/api/suggestions/{id}/submit", id)
                        .session(userSession()))
                .andExpect(status().is2xxSuccessful());

        // Phase 6: Wait for the suggestion to enter DISCUSSING status
        await().atMost(5, SECONDS).untilAsserted(() -> {
            Suggestion s = suggestionRepository.findById(id).orElseThrow();
            assertThat(s.getStatus()).isEqualTo(SuggestionStatus.DISCUSSING);
        });

        // --- Phase 3-4: DISCUSSING → EXPERT_REVIEW → PLANNED ---

        // A PLAN_READY response that causes handleAiResponse to:
        // 1. Create 3 PlanTask rows from the "tasks" array
        // 2. Transition status to EXPERT_REVIEW
        // 3. Kick off the expert review pipeline
        String planReadyJson = "{\"status\":\"PLAN_READY\","
                + "\"message\":\"The plan is ready for review.\","
                + "\"plan\":\"Implement X by doing Y\","
                + "\"tasks\":["
                + "{\"title\":\"Task 1\",\"description\":\"First step\",\"estimatedMinutes\":60},"
                + "{\"title\":\"Task 2\",\"description\":\"Second step\",\"estimatedMinutes\":60},"
                + "{\"title\":\"Task 3\",\"description\":\"Third step\",\"estimatedMinutes\":60}"
                + "]}";

        // Each expert returns APPROVED via supplyAsync so the EXPERT_REVIEW
        // state is observable before the full pipeline finishes
        String expertApprovedJson = "{\"status\":\"APPROVED\","
                + "\"analysis\":\"The plan is comprehensive and well-structured.\"}";

        when(claudeService.expertReview(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), anyInt(), any()))
                .thenAnswer(inv -> CompletableFuture.supplyAsync(() -> expertApprovedJson));

        // Override continueConversation so clarification answers produce a PLAN_READY outcome
        doAnswer(inv -> CompletableFuture.completedFuture(planReadyJson))
                .when(claudeService).continueConversation(any(), any(), any(), any(), any());

        // POST clarification answers — triggers continueConversation mock, which causes
        // handleAiResponse to set EXPERT_REVIEW and start the expert pipeline
        String clarificationBody = "{\"answers\":"
                + "[{\"question\":\"Please clarify X\",\"answer\":\"My answer to X\"}]}";
        mockMvc.perform(post("/api/suggestions/{id}/clarifications", id)
                        .session(userSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(clarificationBody))
                .andExpect(status().isOk());

        // Phase 3: Confirm the suggestion entered EXPERT_REVIEW
        // (expert pipeline starts asynchronously, so status may already be advancing)
        await().atMost(5, SECONDS).untilAsserted(() -> {
            Suggestion s = suggestionRepository.findById(id).orElseThrow();
            assertThat(s.getStatus()).isIn(SuggestionStatus.EXPERT_REVIEW, SuggestionStatus.PLANNED);
        });

        // Phase 4: Wait for all expert approvals to complete and status to reach PLANNED
        await().atMost(30, SECONDS).untilAsserted(() -> {
            Suggestion s = suggestionRepository.findById(id).orElseThrow();
            assertThat(s.getStatus()).isEqualTo(SuggestionStatus.PLANNED);
        });

        // Verify the three plan tasks exist and are all in PENDING state
        mockMvc.perform(get("/api/suggestions/{id}/tasks", id)
                        .session(userSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].status").value("PENDING"))
                .andExpect(jsonPath("$[1].status").value("PENDING"))
                .andExpect(jsonPath("$[2].status").value("PENDING"));

        // --- Phase 5-7: PLANNED → APPROVED → IN_PROGRESS → DEV_COMPLETE → MERGED ---

        // Configure SiteSettings to enable the full execution and auto-merge path
        SiteSettings execSettings = siteSettingsRepository.findAll().get(0);
        execSettings.setTargetRepoUrl("https://github.com/test/repo");
        execSettings.setGithubToken("test-github-token");
        execSettings.setAutoMergePr(true);
        siteSettingsRepository.save(execSettings);

        // Mock git workspace setup: repo clone and branch creation
        when(claudeService.cloneRepository(any(), any())).thenReturn("/tmp/test-repo");
        doNothing().when(claudeService).createBranch(any(), any());

        // Mock per-task execution: return a COMPLETED status for each task
        when(claudeService.executeSingleTask(
                any(), any(), any(), anyInt(), any(), any(), anyInt(), any(), any(), any()))
                .thenAnswer(inv -> {
                    int taskOrder = inv.getArgument(3);
                    return CompletableFuture.completedFuture(
                            "{\"taskOrder\":" + taskOrder + ",\"status\":\"COMPLETED\","
                                    + "\"message\":\"Task " + taskOrder + " done\"}");
                });

        // Mock task completion reviews: both SE and QA reviewers approve each task
        when(claudeService.reviewTaskCompletion(
                any(), any(), any(), any(), anyInt(), any(), any(), any(), any(), any()))
                .thenAnswer(inv -> CompletableFuture.supplyAsync(() ->
                        "{\"status\":\"APPROVED\",\"analysis\":\"Looks good\","
                                + "\"message\":\"Task verified\"}"));

        // Mock git commit/push operations needed before PR creation
        doNothing().when(claudeService).stageAllChanges(any());
        when(claudeService.getStagedDiffSummary(any())).thenReturn("1 file changed");
        when(claudeService.generateCommitMessage(any(), any(), any(), any()))
                .thenReturn("Implement suggestion changes");
        when(claudeService.commitStagedChanges(any(), any())).thenReturn(true);
        doNothing().when(claudeService).pushBranch(any(), any());

        // Mock GitHub PR creation and auto-merge
        when(claudeService.createGitHubPullRequest(any(), any(), any(), any(), any()))
                .thenReturn(Map.of("html_url", "https://github.com/test/repo/pull/1", "number", 1));
        when(claudeService.mergePullRequest(any(), anyInt(), any())).thenReturn(true);

        // Phase 5: Admin approves the suggestion — triggers execution automatically
        mockMvc.perform(post("/api/suggestions/{id}/approve", id)
                        .session(adminSession()))
                .andExpect(status().isOk());

        // Verify APPROVED status is reached (may advance quickly to IN_PROGRESS)
        await().atMost(5, SECONDS).untilAsserted(() -> {
            Suggestion s = suggestionRepository.findById(id).orElseThrow();
            assertThat(s.getStatus()).isIn(
                    SuggestionStatus.APPROVED, SuggestionStatus.IN_PROGRESS,
                    SuggestionStatus.DEV_COMPLETE, SuggestionStatus.FINAL_REVIEW,
                    SuggestionStatus.MERGED);
        });

        // Phase 6: Wait for all three tasks to be executed and verified, reaching DEV_COMPLETE
        await().atMost(30, SECONDS).untilAsserted(() -> {
            Suggestion s = suggestionRepository.findById(id).orElseThrow();
            assertThat(s.getStatus()).isIn(
                    SuggestionStatus.DEV_COMPLETE, SuggestionStatus.FINAL_REVIEW,
                    SuggestionStatus.MERGED);
        });

        // Verify all tasks have been completed
        mockMvc.perform(get("/api/suggestions/{id}/tasks", id)
                        .session(userSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].status").value("COMPLETED"))
                .andExpect(jsonPath("$[1].status").value("COMPLETED"))
                .andExpect(jsonPath("$[2].status").value("COMPLETED"));

        // Phase 7: Wait for the PR to be created and auto-merged, reaching MERGED
        await().atMost(10, SECONDS).untilAsserted(() -> {
            Suggestion s = suggestionRepository.findById(id).orElseThrow();
            assertThat(s.getStatus()).isEqualTo(SuggestionStatus.MERGED);
        });

        // Verify the final state of the suggestion
        mockMvc.perform(get("/api/suggestions/{id}", id)
                        .session(userSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("MERGED"));

        // Verify Slack was notified at least once during the lifecycle
        verify(slackNotificationService, atLeastOnce()).sendNotification(any(), anyString());
    }

    @Test
    void denyPath() throws Exception {
        // Step 1: Create a new suggestion
        String createBody = "{\"title\":\"Suggestion to deny\",\"description\":\"A detailed description\",\"isDraft\":true}";
        String createResponse = mockMvc.perform(post("/api/suggestions")
                        .session(userSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody))
                .andExpect(status().is2xxSuccessful())
                .andReturn().getResponse().getContentAsString();

        long id = objectMapper.readTree(createResponse).get("id").asLong();

        // Step 2: Advance to PLANNED using the helper (skips async AI stages)
        advanceTo(id, SuggestionStatus.PLANNED);

        // Step 3: Admin denies the suggestion
        String denyBody = "{\"reason\":\"Not aligned with roadmap\"}";
        mockMvc.perform(post("/api/suggestions/{id}/deny", id)
                        .session(adminSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(denyBody))
                .andExpect(status().isOk());

        // Step 4: Verify the suggestion is DENIED in the database
        Suggestion denied = suggestionRepository.findById(id).orElseThrow();
        assertThat(denied.getStatus()).isEqualTo(SuggestionStatus.DENIED);

        // Step 5: Attempting to approve an already-denied suggestion must return 4xx
        mockMvc.perform(post("/api/suggestions/{id}/approve", id)
                        .session(adminSession()))
                .andExpect(status().is4xxClientError());
    }
}
