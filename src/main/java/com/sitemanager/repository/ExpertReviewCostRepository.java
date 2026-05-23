package com.sitemanager.repository;

import com.sitemanager.model.ExpertReviewCost;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Access to the per-review cost ledger. Per-suggestion and global roll-ups
 * use the aggregate queries here to compute totals on demand; individual
 * records can also be inspected directly.
 */
@Repository
public interface ExpertReviewCostRepository extends JpaRepository<ExpertReviewCost, Long> {

    /** All cost records for one suggestion, oldest first. */
    List<ExpertReviewCost> findBySuggestionIdOrderByCreatedAtAsc(Long suggestionId);

    /** Newest first across all suggestions, capped to keep the admin page responsive. */
    List<ExpertReviewCost> findTop500ByOrderByCreatedAtDesc();

    /** Number of cost records logged for one suggestion. */
    long countBySuggestionId(Long suggestionId);

    /**
     * Sum of dollar cost across all reviews for one suggestion. Returns
     * {@code null} when no records exist for that suggestion; callers should
     * normalize to {@link BigDecimal#ZERO}.
     */
    @Query("SELECT SUM(c.costUsd) FROM ExpertReviewCost c WHERE c.suggestionId = :suggestionId")
    BigDecimal sumCostBySuggestionId(@Param("suggestionId") Long suggestionId);

    /**
     * Sum of input+output+cache tokens across all reviews for one suggestion.
     * Returns {@code null} when no records exist.
     */
    @Query("SELECT SUM(c.inputTokens + c.outputTokens + c.cacheReadInputTokens + c.cacheCreationInputTokens) "
            + "FROM ExpertReviewCost c WHERE c.suggestionId = :suggestionId")
    Long sumTokensBySuggestionId(@Param("suggestionId") Long suggestionId);

    /**
     * Sum of dollar cost across every recorded review. Returns {@code null}
     * when the ledger is empty.
     */
    @Query("SELECT SUM(c.costUsd) FROM ExpertReviewCost c")
    BigDecimal sumCostGlobal();

    /** Sum of all token streams across every recorded review. */
    @Query("SELECT SUM(c.inputTokens + c.outputTokens + c.cacheReadInputTokens + c.cacheCreationInputTokens) "
            + "FROM ExpertReviewCost c")
    Long sumTokensGlobal();

    /**
     * Sum of dollar cost across reviews recorded at or after {@code since}.
     * Used by the spending limit check when the global reset period is
     * DAILY or MONTHLY — the running total resets at the start of each
     * calendar window. Returns {@code null} when no records exist in the
     * window; callers should normalize to {@link BigDecimal#ZERO}.
     */
    @Query("SELECT SUM(c.costUsd) FROM ExpertReviewCost c WHERE c.createdAt >= :since")
    BigDecimal sumCostSince(@Param("since") Instant since);

    /**
     * Per-suggestion totals across the whole ledger, ordered from highest
     * spend to lowest. Each row is {@code [suggestionId, totalCostUsd,
     * reviewCount]}. Used by the admin dashboard to identify which
     * suggestions are consuming the most budget; callers typically take
     * only the top N.
     */
    @Query("SELECT c.suggestionId, SUM(c.costUsd), COUNT(c) "
            + "FROM ExpertReviewCost c "
            + "GROUP BY c.suggestionId "
            + "ORDER BY SUM(c.costUsd) DESC")
    List<Object[]> findSuggestionSpendTotalsOrderedDesc();

    /**
     * All cost records recorded at or after {@code since}, oldest first.
     * The dashboard uses this to bucket spending into a per-day trend over
     * a configurable window without paying the cost of fetching the full
     * ledger.
     */
    List<ExpertReviewCost> findByCreatedAtGreaterThanEqualOrderByCreatedAtAsc(Instant since);

    /** Newest first, capped to a small page suitable for the recent-activity feed. */
    List<ExpertReviewCost> findTop20ByOrderByCreatedAtDesc();

    /**
     * Sum of dollar cost across reviews recorded in the half-open window
     * {@code [start, end)}. Used by the period archive service to capture
     * the total for a completed daily or monthly window without including
     * any cost from a neighbouring window. Returns {@code null} when the
     * window is empty; callers normalize to {@link BigDecimal#ZERO}.
     */
    @Query("SELECT SUM(c.costUsd) FROM ExpertReviewCost c "
            + "WHERE c.createdAt >= :start AND c.createdAt < :end")
    BigDecimal sumCostBetween(@Param("start") Instant start, @Param("end") Instant end);

    /** Number of cost records in the half-open window {@code [start, end)}. */
    @Query("SELECT COUNT(c) FROM ExpertReviewCost c "
            + "WHERE c.createdAt >= :start AND c.createdAt < :end")
    long countBetween(@Param("start") Instant start, @Param("end") Instant end);

    /**
     * Oldest cost record in the ledger, or empty when nothing has ever
     * been recorded. The archive service uses the timestamp to know how
     * far back it has to walk when no archives exist yet.
     */
    Optional<ExpertReviewCost> findFirstByOrderByCreatedAtAsc();
}
