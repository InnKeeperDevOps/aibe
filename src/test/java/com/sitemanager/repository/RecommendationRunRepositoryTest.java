package com.sitemanager.repository;

import com.sitemanager.model.RecommendationResult;
import com.sitemanager.model.RecommendationRun;
import com.sitemanager.model.enums.RecommendationRunStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class RecommendationRunRepositoryTest {

    @Autowired
    private RecommendationRunRepository runRepository;

    @Autowired
    private RecommendationResultRepository resultRepository;

    @BeforeEach
    void setUp() {
        resultRepository.deleteAll();
        runRepository.deleteAll();
    }

    @Test
    void save_persistsRunAndAssignsId() {
        RecommendationRun run = newRun("task-1", RecommendationRunStatus.IN_PROGRESS);

        RecommendationRun saved = runRepository.save(run);

        assertNotNull(saved.getId());
        assertNotNull(saved.getCreatedAt());
        assertNotNull(saved.getUpdatedAt());
        assertEquals(RecommendationRunStatus.IN_PROGRESS, saved.getStatus());
    }

    @Test
    void findByTaskId_returnsExpectedRun() {
        runRepository.save(newRun("task-A", RecommendationRunStatus.DONE));
        runRepository.save(newRun("task-B", RecommendationRunStatus.ERROR));

        Optional<RecommendationRun> found = runRepository.findByTaskId("task-A");

        assertTrue(found.isPresent());
        assertEquals(RecommendationRunStatus.DONE, found.get().getStatus());
    }

    @Test
    void findByTaskId_returnsEmptyForUnknownTaskId() {
        assertTrue(runRepository.findByTaskId("nonexistent").isEmpty());
    }

    @Test
    void findAllByOrderByCreatedAtDesc_returnsNewestFirst() {
        RecommendationRun older = newRun("older", RecommendationRunStatus.DONE);
        older.setCreatedAt(Instant.now().minusSeconds(120));
        older.setUpdatedAt(Instant.now().minusSeconds(120));
        runRepository.save(older);

        RecommendationRun newer = newRun("newer", RecommendationRunStatus.IN_PROGRESS);
        runRepository.save(newer);

        List<RecommendationRun> runs = runRepository.findAllByOrderByCreatedAtDesc();

        assertEquals(2, runs.size());
        assertEquals("newer", runs.get(0).getTaskId());
        assertEquals("older", runs.get(1).getTaskId());
    }

    @Test
    void findByStatusIn_returnsRunsInGivenStatuses() {
        runRepository.save(newRun("pending-1", RecommendationRunStatus.PENDING));
        runRepository.save(newRun("inprog-1", RecommendationRunStatus.IN_PROGRESS));
        runRepository.save(newRun("done-1", RecommendationRunStatus.DONE));
        runRepository.save(newRun("error-1", RecommendationRunStatus.ERROR));

        List<RecommendationRun> active = runRepository.findByStatusIn(
                List.of(RecommendationRunStatus.PENDING, RecommendationRunStatus.IN_PROGRESS));

        assertEquals(2, active.size());
        List<String> ids = active.stream().map(RecommendationRun::getTaskId).toList();
        assertTrue(ids.contains("pending-1"));
        assertTrue(ids.contains("inprog-1"));
    }

    @Test
    void preUpdate_setsUpdatedAt() throws InterruptedException {
        RecommendationRun run = runRepository.save(newRun("touched", RecommendationRunStatus.IN_PROGRESS));
        Instant originalUpdated = run.getUpdatedAt();
        Thread.sleep(20);

        run.setStatus(RecommendationRunStatus.DONE);
        run.setCompletedAt(Instant.now());
        RecommendationRun updated = runRepository.save(run);

        assertEquals(RecommendationRunStatus.DONE, updated.getStatus());
        assertNotNull(updated.getCompletedAt());
        assertTrue(updated.getUpdatedAt().isAfter(originalUpdated)
                || updated.getUpdatedAt().equals(originalUpdated));
    }

    @Test
    void taskId_isUnique() {
        runRepository.save(newRun("dup", RecommendationRunStatus.IN_PROGRESS));

        RecommendationRun second = newRun("dup", RecommendationRunStatus.PENDING);

        assertThrows(Exception.class, () -> runRepository.saveAndFlush(second));
    }

    @Test
    void resultRepository_findByRunIdOrderByResultOrderAsc_returnsOrdered() {
        RecommendationRun run = runRepository.save(newRun("with-results", RecommendationRunStatus.DONE));

        resultRepository.save(new RecommendationResult(run.getId(), 2, "Third", "third"));
        resultRepository.save(new RecommendationResult(run.getId(), 0, "First", "first"));
        resultRepository.save(new RecommendationResult(run.getId(), 1, "Second", "second"));

        List<RecommendationResult> ordered = resultRepository.findByRunIdOrderByResultOrderAsc(run.getId());

        assertEquals(3, ordered.size());
        assertEquals("First", ordered.get(0).getTitle());
        assertEquals("Second", ordered.get(1).getTitle());
        assertEquals("Third", ordered.get(2).getTitle());
    }

    @Test
    void resultRepository_countByRunId_returnsCountOfResults() {
        RecommendationRun run = runRepository.save(newRun("counted", RecommendationRunStatus.DONE));
        RecommendationRun other = runRepository.save(newRun("other", RecommendationRunStatus.DONE));

        resultRepository.save(new RecommendationResult(run.getId(), 0, "One", "x"));
        resultRepository.save(new RecommendationResult(run.getId(), 1, "Two", "x"));
        resultRepository.save(new RecommendationResult(run.getId(), 2, "Three", "x"));
        resultRepository.save(new RecommendationResult(other.getId(), 0, "Other", "x"));

        assertEquals(3L, resultRepository.countByRunId(run.getId()).longValue());
        assertEquals(1L, resultRepository.countByRunId(other.getId()).longValue());
    }

    @Test
    void resultRepository_countByRunId_returnsZeroWhenNoResults() {
        RecommendationRun run = runRepository.save(newRun("empty", RecommendationRunStatus.ERROR));

        assertEquals(0L, resultRepository.countByRunId(run.getId()).longValue());
    }

    @Test
    void findFiltered_byStatusOnly_returnsMatchingRuns() {
        runRepository.save(newRun("done-1", RecommendationRunStatus.DONE));
        runRepository.save(newRun("done-2", RecommendationRunStatus.DONE));
        runRepository.save(newRun("error-1", RecommendationRunStatus.ERROR));
        runRepository.save(newRun("pending-1", RecommendationRunStatus.PENDING));

        List<RecommendationRun> done = runRepository.findFiltered(RecommendationRunStatus.DONE, null, null);
        assertEquals(2, done.size());
        assertTrue(done.stream().allMatch(r -> r.getStatus() == RecommendationRunStatus.DONE));

        List<RecommendationRun> errors = runRepository.findFiltered(RecommendationRunStatus.ERROR, null, null);
        assertEquals(1, errors.size());
        assertEquals("error-1", errors.get(0).getTaskId());
    }

    @Test
    void findFiltered_byDateRange_returnsRunsWithinWindow() {
        RecommendationRun veryOld = newRun("very-old", RecommendationRunStatus.DONE);
        veryOld.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        veryOld.setUpdatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        runRepository.save(veryOld);

        RecommendationRun midJan = newRun("mid-jan", RecommendationRunStatus.DONE);
        midJan.setCreatedAt(Instant.parse("2026-01-15T12:00:00Z"));
        midJan.setUpdatedAt(Instant.parse("2026-01-15T12:00:00Z"));
        runRepository.save(midJan);

        RecommendationRun febRun = newRun("feb-run", RecommendationRunStatus.DONE);
        febRun.setCreatedAt(Instant.parse("2026-02-10T00:00:00Z"));
        febRun.setUpdatedAt(Instant.parse("2026-02-10T00:00:00Z"));
        runRepository.save(febRun);

        // Only mid-jan falls between Jan 10 and Jan 20
        List<RecommendationRun> mid = runRepository.findFiltered(
                null,
                Instant.parse("2026-01-10T00:00:00Z"),
                Instant.parse("2026-01-20T00:00:00Z"));
        assertEquals(1, mid.size());
        assertEquals("mid-jan", mid.get(0).getTaskId());

        // From Jan 15 onwards picks up mid-jan + feb-run
        List<RecommendationRun> fromMid = runRepository.findFiltered(
                null,
                Instant.parse("2026-01-15T00:00:00Z"),
                null);
        assertEquals(2, fromMid.size());

        // Up to Jan 10 only picks up the very-old one
        List<RecommendationRun> upToTen = runRepository.findFiltered(
                null,
                null,
                Instant.parse("2026-01-10T00:00:00Z"));
        assertEquals(1, upToTen.size());
        assertEquals("very-old", upToTen.get(0).getTaskId());
    }

    @Test
    void findFiltered_byStatusAndDate_combinesBothFilters() {
        RecommendationRun a = newRun("a", RecommendationRunStatus.DONE);
        a.setCreatedAt(Instant.parse("2026-01-15T00:00:00Z"));
        a.setUpdatedAt(Instant.parse("2026-01-15T00:00:00Z"));
        runRepository.save(a);

        RecommendationRun b = newRun("b", RecommendationRunStatus.ERROR);
        b.setCreatedAt(Instant.parse("2026-01-15T00:00:00Z"));
        b.setUpdatedAt(Instant.parse("2026-01-15T00:00:00Z"));
        runRepository.save(b);

        RecommendationRun c = newRun("c", RecommendationRunStatus.DONE);
        c.setCreatedAt(Instant.parse("2026-03-01T00:00:00Z"));
        c.setUpdatedAt(Instant.parse("2026-03-01T00:00:00Z"));
        runRepository.save(c);

        // DONE in Jan only — finds 'a'
        List<RecommendationRun> filtered = runRepository.findFiltered(
                RecommendationRunStatus.DONE,
                Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-01-31T23:59:59Z"));
        assertEquals(1, filtered.size());
        assertEquals("a", filtered.get(0).getTaskId());
    }

    @Test
    void findFiltered_withAllFiltersNull_returnsAllNewestFirst() {
        RecommendationRun older = newRun("older", RecommendationRunStatus.DONE);
        older.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        older.setUpdatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        runRepository.save(older);

        RecommendationRun newer = newRun("newer", RecommendationRunStatus.ERROR);
        newer.setCreatedAt(Instant.parse("2026-04-01T00:00:00Z"));
        newer.setUpdatedAt(Instant.parse("2026-04-01T00:00:00Z"));
        runRepository.save(newer);

        List<RecommendationRun> runs = runRepository.findFiltered(null, null, null);
        assertEquals(2, runs.size());
        assertEquals("newer", runs.get(0).getTaskId());
        assertEquals("older", runs.get(1).getTaskId());
    }

    @Test
    void findFiltered_noMatches_returnsEmpty() {
        runRepository.save(newRun("done-1", RecommendationRunStatus.DONE));

        List<RecommendationRun> errors = runRepository.findFiltered(RecommendationRunStatus.ERROR, null, null);
        assertTrue(errors.isEmpty());
    }

    @Test
    void resultRepository_deleteByRunId_removesOnlyMatchingRows() {
        RecommendationRun runA = runRepository.save(newRun("run-A", RecommendationRunStatus.DONE));
        RecommendationRun runB = runRepository.save(newRun("run-B", RecommendationRunStatus.DONE));

        resultRepository.save(new RecommendationResult(runA.getId(), 0, "A1", "x"));
        resultRepository.save(new RecommendationResult(runA.getId(), 1, "A2", "x"));
        resultRepository.save(new RecommendationResult(runB.getId(), 0, "B1", "x"));

        resultRepository.deleteByRunId(runA.getId());

        assertEquals(0, resultRepository.findByRunIdOrderByResultOrderAsc(runA.getId()).size());
        assertEquals(1, resultRepository.findByRunIdOrderByResultOrderAsc(runB.getId()).size());
    }

    private RecommendationRun newRun(String taskId, RecommendationRunStatus status) {
        RecommendationRun run = new RecommendationRun();
        run.setTaskId(taskId);
        run.setStatus(status);
        return run;
    }
}
