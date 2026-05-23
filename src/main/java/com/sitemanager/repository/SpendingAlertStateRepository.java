package com.sitemanager.repository;

import com.sitemanager.model.SpendingAlertState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Stores one row per (scope, scope key, window key, threshold) alert that
 * has been fired. The uniqueness constraint on those columns guarantees a
 * threshold can only fire once per window, even under concurrent cost
 * recordings.
 */
@Repository
public interface SpendingAlertStateRepository extends JpaRepository<SpendingAlertState, Long> {

    Optional<SpendingAlertState> findByScopeAndScopeKeyAndWindowKeyAndThresholdPercent(
            SpendingAlertState.Scope scope,
            String scopeKey,
            String windowKey,
            int thresholdPercent);

    /**
     * Remove all per-suggestion alert records for a given suggestion so the
     * thresholds become eligible to fire again under a newly configured
     * limit.
     */
    @Modifying
    @Transactional
    @Query("DELETE FROM SpendingAlertState s WHERE s.scope = com.sitemanager.model.SpendingAlertState.Scope.PER_SUGGESTION AND s.scopeKey = :scopeKey")
    void deletePerSuggestionAlerts(@Param("scopeKey") String scopeKey);

    /**
     * Remove all global alert records. Used when the global limit value or
     * reset period is changed so admins can be alerted again under the new
     * configuration.
     */
    @Modifying
    @Transactional
    @Query("DELETE FROM SpendingAlertState s WHERE s.scope = com.sitemanager.model.SpendingAlertState.Scope.GLOBAL")
    void deleteAllGlobalAlerts();

    /**
     * Remove every recorded per-suggestion alert across all suggestions.
     * Used when the per-suggestion cap or the warning-threshold list
     * changes — under the new ceiling, the same percentages target a
     * different spend value, so every suggestion deserves a fresh chance
     * to fire warnings.
     */
    @Modifying
    @Transactional
    @Query("DELETE FROM SpendingAlertState s WHERE s.scope = com.sitemanager.model.SpendingAlertState.Scope.PER_SUGGESTION")
    void deleteAllPerSuggestionAlerts();
}
