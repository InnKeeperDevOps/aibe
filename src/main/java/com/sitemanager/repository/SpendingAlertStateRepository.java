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

    @Modifying
    @Transactional
    @Query("DELETE FROM SpendingAlertState s WHERE s.scope = :scope AND s.scopeKey = :scopeKey")
    void deleteByScopeAndScopeKey(@Param("scope") SpendingAlertState.Scope scope,
                                  @Param("scopeKey") String scopeKey);

    @Modifying
    @Transactional
    @Query("DELETE FROM SpendingAlertState s WHERE s.scope = :scope")
    void deleteByScope(@Param("scope") SpendingAlertState.Scope scope);

    /**
     * Remove all per-suggestion alert records for a given suggestion so the
     * thresholds become eligible to fire again under a newly configured
     * limit.
     */
    default void deletePerSuggestionAlerts(String scopeKey) {
        deleteByScopeAndScopeKey(SpendingAlertState.Scope.PER_SUGGESTION, scopeKey);
    }

    /**
     * Remove all global alert records. Used when the global limit value or
     * reset period is changed so admins can be alerted again under the new
     * configuration.
     */
    default void deleteAllGlobalAlerts() {
        deleteByScope(SpendingAlertState.Scope.GLOBAL);
    }

    /**
     * Remove every recorded per-suggestion alert across all suggestions.
     * Used when the per-suggestion cap or the warning-threshold list
     * changes — under the new ceiling, the same percentages target a
     * different spend value, so every suggestion deserves a fresh chance
     * to fire warnings.
     */
    default void deleteAllPerSuggestionAlerts() {
        deleteByScope(SpendingAlertState.Scope.PER_SUGGESTION);
    }
}
