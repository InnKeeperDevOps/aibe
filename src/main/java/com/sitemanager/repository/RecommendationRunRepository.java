package com.sitemanager.repository;

import com.sitemanager.model.RecommendationRun;
import com.sitemanager.model.enums.RecommendationRunStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface RecommendationRunRepository extends JpaRepository<RecommendationRun, Long> {

    Optional<RecommendationRun> findByTaskId(String taskId);

    List<RecommendationRun> findAllByOrderByCreatedAtDesc();

    List<RecommendationRun> findByStatusIn(List<RecommendationRunStatus> statuses);

    /**
     * Filtered history query that supports any combination of status / date-range
     * filters. Any null parameter is treated as "no filter on this field" so the
     * caller can apply just the filters the user actually selected.
     */
    @Query("SELECT r FROM RecommendationRun r WHERE " +
            "(:status IS NULL OR r.status = :status) AND " +
            "(:from IS NULL OR r.createdAt >= :from) AND " +
            "(:to IS NULL OR r.createdAt <= :to) " +
            "ORDER BY r.createdAt DESC")
    List<RecommendationRun> findFiltered(@Param("status") RecommendationRunStatus status,
                                          @Param("from") Instant from,
                                          @Param("to") Instant to);
}
