package com.sitemanager.service;

import com.sitemanager.repository.SiteSettingsRepository;
import com.sitemanager.repository.ClaudeCliLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that buildEvaluationPrompt() always mandates NEEDS_CLARIFICATION
 * on the initial evaluation, regardless of how detailed the suggestion is.
 */
@ExtendWith(MockitoExtension.class)
class ClaudeServiceEvaluationPromptTest {

    @Mock
    private SiteSettingsRepository settingsRepository;

    @Mock
    private ClaudeCliLogRepository cliLogRepository;

    private ClaudeService service;

    private static final String TITLE = "Add dark mode";
    private static final String SIMPLE_DESC = "Toggle between light and dark themes.";
    private static final String DETAILED_DESC =
            "Add a theme toggle button in the header. Store the preference in local storage. " +
            "Apply a CSS class to the root element to switch color schemes. " +
            "Default to the OS preference using prefers-color-scheme. " +
            "Support light, dark, and system-auto modes. " +
            "Persist the choice across sessions and page reloads.";
    private static final String REPO_URL = "https://github.com/example/site";

    @BeforeEach
    void setUp() {
        service = new ClaudeService(settingsRepository, cliLogRepository);
    }

    // -------------------------------------------------------------------------
    // Status mandate
    // -------------------------------------------------------------------------

    @Test
    void prompt_alwaysMandatesNeedsClarificationStatus() {
        String prompt = service.buildEvaluationPrompt(TITLE, SIMPLE_DESC, REPO_URL);
        assertThat(prompt).contains("NEEDS_CLARIFICATION");
    }

    @Test
    void prompt_explicitlyForbidsPlanReadyStatus() {
        String prompt = service.buildEvaluationPrompt(TITLE, SIMPLE_DESC, REPO_URL);
        // Must instruct Claude NOT to use PLAN_READY on first evaluation
        assertThat(prompt).contains("PLAN_READY");
        assertThat(prompt).containsPattern("(?i)Do NOT respond with.*PLAN_READY|not allowed.*PLAN_READY|PLAN_READY.*not allowed");
    }

    @Test
    void prompt_mandatesNeedsClarificationEvenForDetailedSuggestion() {
        String prompt = service.buildEvaluationPrompt(TITLE, DETAILED_DESC, REPO_URL);
        assertThat(prompt).contains("NEEDS_CLARIFICATION");
        // The mandatory rule statement must appear regardless of description length
        assertThat(prompt).containsIgnoringCase("always");
    }

    // -------------------------------------------------------------------------
    // Question count mandate
    // -------------------------------------------------------------------------

    @Test
    void prompt_mandatesTwoToFourQuestions() {
        String prompt = service.buildEvaluationPrompt(TITLE, SIMPLE_DESC, REPO_URL);
        assertThat(prompt).containsPattern("2.?4|2 to 4|two.?four");
    }

    @Test
    void prompt_requiresQuestionsArray() {
        String prompt = service.buildEvaluationPrompt(TITLE, SIMPLE_DESC, REPO_URL);
        assertThat(prompt).contains("\"questions\"");
    }

    // -------------------------------------------------------------------------
    // Question focus: outcomes and behavior, not technical details
    // -------------------------------------------------------------------------

    @Test
    void prompt_instructsQuestionsAboutOutcomes() {
        String prompt = service.buildEvaluationPrompt(TITLE, SIMPLE_DESC, REPO_URL);
        assertThat(prompt).containsIgnoringCase("outcome");
    }

    @Test
    void prompt_instructsQuestionsAboutPrioritiesOrScope() {
        String prompt = service.buildEvaluationPrompt(TITLE, SIMPLE_DESC, REPO_URL);
        assertThat(prompt).satisfiesAnyOf(
                p -> assertThat(p).containsIgnoringCase("priority"),
                p -> assertThat(p).containsIgnoringCase("scope")
        );
    }

    @Test
    void prompt_instructsQuestionsInPlainLanguage() {
        String prompt = service.buildEvaluationPrompt(TITLE, SIMPLE_DESC, REPO_URL);
        assertThat(prompt).satisfiesAnyOf(
                p -> assertThat(p).containsIgnoringCase("plain"),
                p -> assertThat(p).containsIgnoringCase("non-technical"),
                p -> assertThat(p).containsIgnoringCase("everyday language")
        );
    }

    @Test
    void prompt_doesNotInstructTechnicalQuestions() {
        String prompt = service.buildEvaluationPrompt(TITLE, SIMPLE_DESC, REPO_URL);
        // Should instruct Claude to avoid technical details in questions
        assertThat(prompt).containsIgnoringCase("never on technical");
    }

    // -------------------------------------------------------------------------
    // JSON format spec preserved for downstream parsing
    // -------------------------------------------------------------------------

    @Test
    void prompt_includesMessageField() {
        String prompt = service.buildEvaluationPrompt(TITLE, SIMPLE_DESC, REPO_URL);
        assertThat(prompt).contains("\"message\"");
    }

    @Test
    void prompt_doesNotIncludePlanField() {
        String prompt = service.buildEvaluationPrompt(TITLE, SIMPLE_DESC, REPO_URL);
        // The prompt must explicitly tell Claude not to include a plan field
        assertThat(prompt).containsIgnoringCase("Do NOT include a \"plan\"");
    }

    @Test
    void prompt_doesNotIncludeTasksFieldInstruction() {
        String prompt = service.buildEvaluationPrompt(TITLE, SIMPLE_DESC, REPO_URL);
        // The prompt must explicitly tell Claude not to include a tasks field
        assertThat(prompt).containsIgnoringCase("tasks");
        assertThat(prompt).containsIgnoringCase("Do NOT include");
    }

    // -------------------------------------------------------------------------
    // Suggestion content injection
    // -------------------------------------------------------------------------

    @Test
    void prompt_includesSuggestionTitle() {
        String prompt = service.buildEvaluationPrompt(TITLE, SIMPLE_DESC, REPO_URL);
        assertThat(prompt).contains(TITLE);
    }

    @Test
    void prompt_includesSuggestionDescription() {
        String prompt = service.buildEvaluationPrompt(TITLE, SIMPLE_DESC, REPO_URL);
        assertThat(prompt).contains(SIMPLE_DESC);
    }

    @Test
    void prompt_includesRepoUrl() {
        String prompt = service.buildEvaluationPrompt(TITLE, SIMPLE_DESC, REPO_URL);
        assertThat(prompt).contains(REPO_URL);
    }

    @Test
    void prompt_handlesNullRepoUrl() {
        String prompt = service.buildEvaluationPrompt(TITLE, SIMPLE_DESC, null);
        assertThat(prompt).contains("not configured");
    }
}
