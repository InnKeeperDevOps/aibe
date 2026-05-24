package com.sitemanager.config;

import com.sitemanager.controller.RecommendationController;
import com.sitemanager.model.RecommendationResult;
import com.sitemanager.model.RecommendationRun;
import com.sitemanager.model.enums.RecommendationRunStatus;
import com.sitemanager.repository.RecommendationResultRepository;
import com.sitemanager.repository.RecommendationRunRepository;
import com.sitemanager.repository.SuggestionRepository;
import com.sitemanager.service.ClaudeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SpringBootTest
class RecommendationResumeRunnerTest {

    @Autowired
    private RecommendationResumeRunner runner;

    @Autowired
    private RecommendationRunRepository runRepository;

    @Autowired
    private RecommendationResultRepository resultRepository;

    @Autowired
    private SuggestionRepository suggestionRepository;

    @SpyBean
    private RecommendationController recommendationController;

    @MockBean
    private ClaudeService claudeService;

    private static final String VALID_RESPONSE =
            "[{\"title\":\"Add notifications\",\"description\":\"Notify users on changes.\"}]";

    @BeforeEach
    void setUp() {
        resultRepository.deleteAll();
        runRepository.deleteAll();
        suggestionRepository.deleteAll();
        reset(recommendationController);
        when(claudeService.getMainRepoDir()).thenReturn("/tmp/test-main-repo");
        when(claudeService.getRecommendations(any())).thenReturn(VALID_RESPONSE);
    }

    private RecommendationRun saveRun(String taskId, RecommendationRunStatus status) {
        RecommendationRun run = new RecommendationRun();
        run.setTaskId(taskId);
        run.setStatus(status);
        run.setRequestedByUsername("admin");
        return runRepository.save(run);
    }

    @Test
    void resumeInterruptedRuns_picksUpInProgressRun() {
        RecommendationRun run = saveRun("inprog-task", RecommendationRunStatus.IN_PROGRESS);

        int resumed = runner.resumeInterruptedRuns();

        assertEquals(1, resumed);
        ArgumentCaptor<RecommendationRun> captor = ArgumentCaptor.forClass(RecommendationRun.class);
        verify(recommendationController, times(1)).enqueueRun(captor.capture());
        assertEquals(run.getTaskId(), captor.getValue().getTaskId());
    }

    @Test
    void resumeInterruptedRuns_picksUpPendingRun() {
        saveRun("pending-task", RecommendationRunStatus.PENDING);

        int resumed = runner.resumeInterruptedRuns();

        assertEquals(1, resumed);
        verify(recommendationController, times(1)).enqueueRun(any(RecommendationRun.class));
    }

    @Test
    void resumeInterruptedRuns_ignoresDoneAndErrorRuns() {
        saveRun("done-task", RecommendationRunStatus.DONE);
        saveRun("error-task", RecommendationRunStatus.ERROR);

        int resumed = runner.resumeInterruptedRuns();

        assertEquals(0, resumed);
        verify(recommendationController, never()).enqueueRun(any());
    }

    @Test
    void resumeInterruptedRuns_clearsPartialResultsBeforeResubmitting() {
        RecommendationRun run = saveRun("partial-task", RecommendationRunStatus.IN_PROGRESS);
        resultRepository.save(new RecommendationResult(run.getId(), 0, "stale-1", "from prior attempt"));
        resultRepository.save(new RecommendationResult(run.getId(), 1, "stale-2", "from prior attempt"));

        // Stub enqueueRun so the background thread does not race with our assertion.
        doNothing().when(recommendationController).enqueueRun(any());

        runner.resumeInterruptedRuns();

        List<RecommendationResult> remaining =
                resultRepository.findByRunIdOrderByResultOrderAsc(run.getId());
        assertTrue(remaining.isEmpty(), "Partial results from the prior attempt should be deleted");
    }

    @Test
    void resumeInterruptedRuns_setsStatusToInProgressAndClearsError() {
        RecommendationRun pending = saveRun("pending-2", RecommendationRunStatus.PENDING);
        pending.setErrorMessage("stale error from before");
        runRepository.save(pending);

        doNothing().when(recommendationController).enqueueRun(any());

        runner.resumeInterruptedRuns();

        RecommendationRun reloaded = runRepository.findByTaskId("pending-2").orElseThrow();
        assertEquals(RecommendationRunStatus.IN_PROGRESS, reloaded.getStatus());
        assertNull(reloaded.getErrorMessage());
    }

    @Test
    void resumeInterruptedRuns_guardsAgainstDuplicateResumption() {
        saveRun("once-only", RecommendationRunStatus.IN_PROGRESS);
        doNothing().when(recommendationController).enqueueRun(any());

        int firstCallResumed = runner.resumeInterruptedRuns();
        int secondCallResumed = runner.resumeInterruptedRuns();

        assertEquals(1, firstCallResumed);
        assertEquals(0, secondCallResumed,
                "Same task should not be resumed twice in the same process");
        verify(recommendationController, times(1)).enqueueRun(any());
    }

    @Test
    void resumeInterruptedRuns_withNoInterruptedRuns_doesNothing() {
        int resumed = runner.resumeInterruptedRuns();

        assertEquals(0, resumed);
        verify(recommendationController, never()).enqueueRun(any());
    }

    @Test
    void resumeInterruptedRuns_resumesMultipleRuns() {
        saveRun("multi-1", RecommendationRunStatus.IN_PROGRESS);
        saveRun("multi-2", RecommendationRunStatus.PENDING);
        saveRun("multi-3", RecommendationRunStatus.IN_PROGRESS);
        saveRun("ignored-done", RecommendationRunStatus.DONE);

        doNothing().when(recommendationController).enqueueRun(any());

        int resumed = runner.resumeInterruptedRuns();

        assertEquals(3, resumed);
        verify(recommendationController, times(3)).enqueueRun(any());
    }

    @Test
    void resumeInterruptedRuns_endToEnd_completesRunAndPersistsResults() throws Exception {
        RecommendationRun run = saveRun("e2e-task", RecommendationRunStatus.IN_PROGRESS);

        runner.resumeInterruptedRuns();

        // Poll until the background job completes; the in-memory test should be quick.
        Instant deadline = Instant.now().plusSeconds(15);
        RecommendationRun reloaded = null;
        while (Instant.now().isBefore(deadline)) {
            reloaded = runRepository.findByTaskId("e2e-task").orElseThrow();
            if (reloaded.getStatus() == RecommendationRunStatus.DONE
                    || reloaded.getStatus() == RecommendationRunStatus.ERROR) {
                break;
            }
            Thread.sleep(100);
        }
        assertNotNull(reloaded);
        assertEquals(RecommendationRunStatus.DONE, reloaded.getStatus(),
                "Resumed run should reach DONE after the AI call returns");

        List<RecommendationResult> stored =
                resultRepository.findByRunIdOrderByResultOrderAsc(run.getId());
        assertEquals(1, stored.size());
        assertEquals("Add notifications", stored.get(0).getTitle());
    }
}
