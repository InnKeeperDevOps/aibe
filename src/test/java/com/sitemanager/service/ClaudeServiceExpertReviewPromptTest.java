package com.sitemanager.service;

import com.sitemanager.model.enums.ExpertRole;
import com.sitemanager.repository.SiteSettingsRepository;
import com.sitemanager.repository.ClaudeCliLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that expertReview() builds the correct prompt depending on whether
 * the reviewer is the Project Owner or a standard downstream expert, and that
 * owner-lock context is injected when locked sections exist.
 */
@ExtendWith(MockitoExtension.class)
class ClaudeServiceExpertReviewPromptTest {

    @Mock
    private SiteSettingsRepository settingsRepository;

    @Mock
    private ClaudeCliLogRepository cliLogRepository;

    private ClaudeService service;

    private static final String TITLE = "Add dark mode";
    private static final String DESCRIPTION = "Allow users to switch between light and dark themes.";
    private static final String PLAN = "1. Add theme toggle\n2. Store preference";
    private static final String TASKS_JSON = "[{\"title\":\"Add toggle\",\"displayTitle\":\"Add toggle\"}]";
    private static final String PREV_NOTES = "Architect: looks good.";

    @BeforeEach
    void setUp() {
        service = new ClaudeService(settingsRepository, cliLogRepository);
    }

    // -------------------------------------------------------------------------
    // PROJECT_OWNER prompt tests
    // -------------------------------------------------------------------------

    @Test
    void projectOwnerPrompt_includesLockedTaskIndicesResponseField() {
        String prompt = service.buildProjectOwnerReviewPrompt(
                ExpertRole.PROJECT_OWNER.getReviewPrompt(),
                TITLE, DESCRIPTION, PLAN, TASKS_JSON, null);

        assertThat(prompt).contains("lockedTaskIndices");
    }

    @Test
    void projectOwnerPrompt_includesSuggestionTitleAndDescription() {
        String prompt = service.buildProjectOwnerReviewPrompt(
                ExpertRole.PROJECT_OWNER.getReviewPrompt(),
                TITLE, DESCRIPTION, PLAN, TASKS_JSON, null);

        assertThat(prompt).contains(TITLE);
        assertThat(prompt).contains(DESCRIPTION);
    }

    @Test
    void projectOwnerPrompt_includesCurrentPlan() {
        String prompt = service.buildProjectOwnerReviewPrompt(
                ExpertRole.PROJECT_OWNER.getReviewPrompt(),
                TITLE, DESCRIPTION, PLAN, TASKS_JSON, null);

        assertThat(prompt).contains(PLAN);
    }

    @Test
    void projectOwnerPrompt_includesCurrentTasks() {
        String prompt = service.buildProjectOwnerReviewPrompt(
                ExpertRole.PROJECT_OWNER.getReviewPrompt(),
                TITLE, DESCRIPTION, PLAN, TASKS_JSON, null);

        assertThat(prompt).contains(TASKS_JSON);
    }

    @Test
    void projectOwnerPrompt_includesPreviousNotesWhenPresent() {
        String prompt = service.buildProjectOwnerReviewPrompt(
                ExpertRole.PROJECT_OWNER.getReviewPrompt(),
                TITLE, DESCRIPTION, PLAN, TASKS_JSON, PREV_NOTES);

        assertThat(prompt).contains(PREV_NOTES);
    }

    @Test
    void projectOwnerPrompt_omitsPreviousNotesWhenBlank() {
        String prompt = service.buildProjectOwnerReviewPrompt(
                ExpertRole.PROJECT_OWNER.getReviewPrompt(),
                TITLE, DESCRIPTION, PLAN, TASKS_JSON, "");

        assertThat(prompt).doesNotContain("Previous expert reviews");
    }

    @Test
    void projectOwnerPrompt_requiresLockedTaskIndicesInBothResponseStatuses() {
        String prompt = service.buildProjectOwnerReviewPrompt(
                ExpertRole.PROJECT_OWNER.getReviewPrompt(),
                TITLE, DESCRIPTION, PLAN, TASKS_JSON, null);

        // lockedTaskIndices must appear in APPROVED and CHANGES_PROPOSED response examples
        long occurrences = prompt.chars()
                .filter(c -> prompt.indexOf("lockedTaskIndices") >= 0)
                .count();
        assertThat(occurrences).isGreaterThan(0);
        // Both status formats must include it
        assertThat(prompt).contains("\"status\": \"APPROVED\"");
        assertThat(prompt).contains("\"status\": \"CHANGES_PROPOSED\"");
        // lockedTaskIndices appears at least twice (once per response format)
        int first = prompt.indexOf("lockedTaskIndices");
        int second = prompt.indexOf("lockedTaskIndices", first + 1);
        assertThat(second).isGreaterThan(first);
    }

    @Test
    void projectOwnerPrompt_includesProjectOwnerRoleInstructions() {
        String prompt = service.buildProjectOwnerReviewPrompt(
                ExpertRole.PROJECT_OWNER.getReviewPrompt(),
                TITLE, DESCRIPTION, PLAN, null, null);

        // The PROJECT_OWNER role prompt must be embedded
        assertThat(prompt).contains(ExpertRole.PROJECT_OWNER.getReviewPrompt());
    }

    @Test
    void projectOwnerPrompt_handlesNullTasksJson() {
        String prompt = service.buildProjectOwnerReviewPrompt(
                ExpertRole.PROJECT_OWNER.getReviewPrompt(),
                TITLE, DESCRIPTION, PLAN, null, null);

        // Should not throw and should not contain task block
        assertThat(prompt).doesNotContain("Current Tasks:");
    }

    // -------------------------------------------------------------------------
    // Standard expert prompt — no owner-locked sections
    // -------------------------------------------------------------------------

    @Test
    void standardExpertPrompt_noLockedSections_doesNotIncludeOwnerLockSection() {
        String prompt = service.buildStandardExpertReviewPrompt(
                ExpertRole.SOFTWARE_ARCHITECT.getReviewPrompt(),
                TITLE, DESCRIPTION, PLAN, TASKS_JSON, null,
                1, Collections.emptyList());

        assertThat(prompt).doesNotContain("OWNER-LOCKED TASKS");
    }

    @Test
    void standardExpertPrompt_noLockedSections_nullList_doesNotIncludeOwnerLockSection() {
        String prompt = service.buildStandardExpertReviewPrompt(
                ExpertRole.SOFTWARE_ARCHITECT.getReviewPrompt(),
                TITLE, DESCRIPTION, PLAN, TASKS_JSON, null,
                1, null);

        assertThat(prompt).doesNotContain("OWNER-LOCKED TASKS");
    }

    @Test
    void standardExpertPrompt_withLockedSections_includesOwnerLockSection() {
        String prompt = service.buildStandardExpertReviewPrompt(
                ExpertRole.SOFTWARE_ARCHITECT.getReviewPrompt(),
                TITLE, DESCRIPTION, PLAN, TASKS_JSON, null,
                1, List.of(0, 2));

        assertThat(prompt).contains("OWNER-LOCKED TASKS");
        assertThat(prompt).contains("[0, 2]");
    }

    @Test
    void standardExpertPrompt_withLockedSections_instructsNotToRemoveThem() {
        String prompt = service.buildStandardExpertReviewPrompt(
                ExpertRole.SOFTWARE_ARCHITECT.getReviewPrompt(),
                TITLE, DESCRIPTION, PLAN, TASKS_JSON, null,
                1, List.of(1));

        assertThat(prompt).contains("CANNOT remove them");
    }

    @Test
    void standardExpertPrompt_withLockedSections_allowsImplementationChanges() {
        String prompt = service.buildStandardExpertReviewPrompt(
                ExpertRole.SOFTWARE_ARCHITECT.getReviewPrompt(),
                TITLE, DESCRIPTION, PLAN, TASKS_JSON, null,
                1, List.of(0));

        assertThat(prompt).contains("implementation approach");
    }

    @Test
    void standardExpertPrompt_includesSuggestionTitleAndPlan() {
        String prompt = service.buildStandardExpertReviewPrompt(
                ExpertRole.QA_ENGINEER.getReviewPrompt(),
                TITLE, DESCRIPTION, PLAN, TASKS_JSON, null,
                1, Collections.emptyList());

        assertThat(prompt).contains(TITLE);
        assertThat(prompt).contains(DESCRIPTION);
        assertThat(prompt).contains(PLAN);
    }

    // -------------------------------------------------------------------------
    // Re-review round (round 2+) convergence prompt tests
    // -------------------------------------------------------------------------

    @Test
    void standardExpertPrompt_round1_doesNotIncludeReReviewRules() {
        String prompt = service.buildStandardExpertReviewPrompt(
                ExpertRole.SECURITY_ENGINEER.getReviewPrompt(),
                TITLE, DESCRIPTION, PLAN, TASKS_JSON, null,
                1, Collections.emptyList());

        assertThat(prompt).doesNotContain("RE-REVIEW RULES");
    }

    @Test
    void standardExpertPrompt_round2_includesReReviewRules() {
        String prompt = service.buildStandardExpertReviewPrompt(
                ExpertRole.SECURITY_ENGINEER.getReviewPrompt(),
                TITLE, DESCRIPTION, PLAN, TASKS_JSON, null,
                2, Collections.emptyList());

        assertThat(prompt).contains("ROUND 2 RE-REVIEW RULES");
    }

    @Test
    void standardExpertPrompt_round2_withLockedSections_includesOwnerLockInReReviewRules() {
        String prompt = service.buildStandardExpertReviewPrompt(
                ExpertRole.SECURITY_ENGINEER.getReviewPrompt(),
                TITLE, DESCRIPTION, PLAN, TASKS_JSON, null,
                2, List.of(0, 1));

        assertThat(prompt).contains("ROUND 2 RE-REVIEW RULES");
        assertThat(prompt).contains("owner-locked tasks");
        assertThat(prompt).contains("[0, 1]");
    }

    @Test
    void standardExpertPrompt_round2_withNoLockedSections_noOwnerLockInReReviewRules() {
        String prompt = service.buildStandardExpertReviewPrompt(
                ExpertRole.SECURITY_ENGINEER.getReviewPrompt(),
                TITLE, DESCRIPTION, PLAN, TASKS_JSON, null,
                2, Collections.emptyList());

        assertThat(prompt).contains("ROUND 2 RE-REVIEW RULES");
        assertThat(prompt).doesNotContain("owner-locked tasks");
    }

    @Test
    void standardExpertPrompt_round3_includesCorrectRoundNumber() {
        String prompt = service.buildStandardExpertReviewPrompt(
                ExpertRole.PRODUCT_MANAGER.getReviewPrompt(),
                TITLE, DESCRIPTION, PLAN, TASKS_JSON, null,
                3, Collections.emptyList());

        assertThat(prompt).contains("ROUND 3 RE-REVIEW RULES");
    }
}
