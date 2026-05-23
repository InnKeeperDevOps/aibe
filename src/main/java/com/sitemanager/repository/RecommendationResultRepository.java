package com.sitemanager.repository;

import com.sitemanager.model.RecommendationResult;
import com.sitemanager.model.enums.RecommendationResultStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Repository
public interface RecommendationResultRepository extends JpaRepository<RecommendationResult, Long> {

    List<RecommendationResult> findByRunIdOrderByResultOrderAsc(Long runId);

    List<RecommendationResult> findByRunIdAndStatusOrderByResultOrderAsc(Long runId, RecommendationResultStatus status);

    Long countByRunId(Long runId);

    @Transactional
    void deleteByRunId(Long runId);
}
