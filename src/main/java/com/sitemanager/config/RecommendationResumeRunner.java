package com.sitemanager.config;

import com.sitemanager.controller.RecommendationController;
import com.sitemanager.model.RecommendationRun;
import com.sitemanager.model.enums.RecommendationRunStatus;
import com.sitemanager.repository.RecommendationResultRepository;
import com.sitemanager.repository.RecommendationRunRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * On application startup, finds any recommendation run that was still
 * PENDING or IN_PROGRESS when the previous process exited and re-runs it
 * from scratch. The underlying Claude call is atomic with no incremental
 * checkpoint, so any partial results from the interrupted attempt are
 * deleted before the run is re-submitted to the background executor.
 *
 * <p>Runs after {@link DataMigrationRunner} (which is {@code @Order(1)})
 * so schema fixups complete first.
 */
@Component
@Order(10)
public class RecommendationResumeRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(RecommendationResumeRunner.class);

    private final RecommendationRunRepository runRepository;
    private final RecommendationResultRepository resultRepository;
    private final RecommendationController recommendationController;

    private final Set<String> alreadyResumed = ConcurrentHashMap.newKeySet();

    public RecommendationResumeRunner(RecommendationRunRepository runRepository,
                                      RecommendationResultRepository resultRepository,
                                      RecommendationController recommendationController) {
        this.runRepository = runRepository;
        this.resultRepository = resultRepository;
        this.recommendationController = recommendationController;
    }

    @Override
    public void run(String... args) {
        resumeInterruptedRuns();
    }

    /**
     * Resume every recommendation run still marked PENDING or IN_PROGRESS.
     * Safe to call more than once: each {@code taskId} is only resumed a
     * single time per JVM, guarded by {@link #alreadyResumed}.
     *
     * @return the number of runs actually resubmitted on this call
     */
    public int resumeInterruptedRuns() {
        List<RecommendationRun> interrupted = runRepository.findByStatusIn(
                List.of(RecommendationRunStatus.PENDING, RecommendationRunStatus.IN_PROGRESS));

        if (interrupted.isEmpty()) {
            return 0;
        }

        log.info("[RECOMMENDATIONS] Found {} interrupted run(s) to resume after restart", interrupted.size());

        int resumed = 0;
        for (RecommendationRun run : interrupted) {
            if (!alreadyResumed.add(run.getTaskId())) {
                log.info("[RECOMMENDATIONS] Skipping {} — already resumed in this process", run.getTaskId());
                continue;
            }

            log.info("[RECOMMENDATIONS] Resuming interrupted run {} (was {})",
                    run.getTaskId(), run.getStatus());

            // Single Claude call has no checkpoint, so any partial results from
            // the prior attempt would be inconsistent with the new run. Delete
            // them and start fresh.
            resultRepository.deleteByRunId(run.getId());

            run.setStatus(RecommendationRunStatus.IN_PROGRESS);
            run.setErrorMessage(null);
            runRepository.save(run);

            recommendationController.enqueueRun(run);
            resumed++;
        }

        log.info("[RECOMMENDATIONS] Resumed {} run(s) after restart", resumed);
        return resumed;
    }
}
