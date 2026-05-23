package com.sitemanager.repository;

import com.sitemanager.model.SpendingPeriodArchive;
import com.sitemanager.model.enums.CostResetPeriod;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Stores frozen summaries of completed daily / monthly spending windows.
 * Lookups by {@code (periodType, periodStart)} are unique, which makes the
 * archive write idempotent under concurrent invocations.
 */
@Repository
public interface SpendingPeriodArchiveRepository extends JpaRepository<SpendingPeriodArchive, Long> {

    /** Most recently archived window for one period type, if any. */
    Optional<SpendingPeriodArchive> findFirstByPeriodTypeOrderByPeriodStartDesc(
            CostResetPeriod periodType);

    /** Quick existence check used to short-circuit re-archival attempts. */
    boolean existsByPeriodTypeAndPeriodStart(CostResetPeriod periodType, Instant periodStart);

    /**
     * History for one period type, newest first, capped to keep the
     * dashboard payload bounded. The cap is generous enough to show a
     * year's worth of months or a few weeks of days.
     */
    List<SpendingPeriodArchive> findTop90ByPeriodTypeOrderByPeriodStartDesc(
            CostResetPeriod periodType);

    /** All archives across both period types, newest first. */
    List<SpendingPeriodArchive> findAllByOrderByPeriodStartDesc();
}
