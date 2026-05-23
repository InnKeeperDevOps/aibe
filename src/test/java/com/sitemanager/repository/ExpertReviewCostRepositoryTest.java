package com.sitemanager.repository;

import com.sitemanager.model.ExpertReviewCost;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the aggregate roll-up queries on {@link ExpertReviewCostRepository}:
 * per-suggestion and global sums and counts.
 */
@SpringBootTest
class ExpertReviewCostRepositoryTest {

    @Autowired
    private ExpertReviewCostRepository costRepository;

    @BeforeEach
    void setUp() {
        costRepository.deleteAll();
    }

    private ExpertReviewCost record(Long suggestionId, String expert,
                                     long input, long output, long cacheRead, long cacheCreate,
                                     String cost) {
        ExpertReviewCost r = new ExpertReviewCost();
        r.setSuggestionId(suggestionId);
        r.setExpertName(expert);
        r.setOperationType("expert-review:" + expert);
        r.setReviewSessionId("session-" + suggestionId + "-" + expert);
        r.setModel("claude-test");
        r.setInputTokens(input);
        r.setOutputTokens(output);
        r.setCacheReadInputTokens(cacheRead);
        r.setCacheCreationInputTokens(cacheCreate);
        r.setCostUsd(new BigDecimal(cost));
        r.setDurationMs(1000);
        return r;
    }

    @Test
    void countAndSums_emptyLedgerReturnsZerosAndNulls() {
        assertThat(costRepository.countBySuggestionId(1L)).isZero();
        assertThat(costRepository.sumCostBySuggestionId(1L)).isNull();
        assertThat(costRepository.sumTokensBySuggestionId(1L)).isNull();
        assertThat(costRepository.sumCostGlobal()).isNull();
        assertThat(costRepository.sumTokensGlobal()).isNull();
    }

    @Test
    void perSuggestionAggregates_onlyReflectThatSuggestion() {
        costRepository.save(record(1L, "QA",       10, 20, 30, 40, "0.10"));
        costRepository.save(record(1L, "Security", 1,  1,  1,  1,  "0.20"));
        costRepository.save(record(2L, "QA",       5,  5,  0,  0,  "0.50"));

        assertThat(costRepository.countBySuggestionId(1L)).isEqualTo(2L);
        assertThat(costRepository.sumCostBySuggestionId(1L))
                .isEqualByComparingTo(new BigDecimal("0.30"));
        // (10+20+30+40) + (1+1+1+1) = 100 + 4 = 104
        assertThat(costRepository.sumTokensBySuggestionId(1L)).isEqualTo(104L);

        assertThat(costRepository.countBySuggestionId(2L)).isEqualTo(1L);
        assertThat(costRepository.sumCostBySuggestionId(2L))
                .isEqualByComparingTo(new BigDecimal("0.50"));
        assertThat(costRepository.sumTokensBySuggestionId(2L)).isEqualTo(10L);
    }

    @Test
    void globalAggregates_sumEveryRow() {
        costRepository.save(record(1L, "QA",       10, 20, 0,  0,  "0.10"));
        costRepository.save(record(1L, "Security", 0,  0,  10, 0,  "0.20"));
        costRepository.save(record(2L, "QA",       5,  5,  0,  10, "0.70"));

        assertThat(costRepository.count()).isEqualTo(3L);
        assertThat(costRepository.sumCostGlobal())
                .isEqualByComparingTo(new BigDecimal("1.00"));
        // 30 + 10 + 20 = 60
        assertThat(costRepository.sumTokensGlobal()).isEqualTo(60L);
    }

    @Test
    void findSuggestionSpendTotalsOrderedDesc_groupsAndOrdersByTotalCost() {
        costRepository.save(record(1L, "QA",       1, 1, 0, 0, "0.50"));
        costRepository.save(record(1L, "Security", 1, 1, 0, 0, "1.50"));
        costRepository.save(record(2L, "QA",       1, 1, 0, 0, "0.10"));
        costRepository.save(record(3L, "QA",       1, 1, 0, 0, "5.00"));

        List<Object[]> totals = costRepository.findSuggestionSpendTotalsOrderedDesc();

        assertThat(totals).hasSize(3);
        // Order is by total spend descending: 3 (5.00), 1 (2.00), 2 (0.10).
        assertThat(((Number) totals.get(0)[0]).longValue()).isEqualTo(3L);
        assertThat((BigDecimal) totals.get(0)[1]).isEqualByComparingTo("5.00");
        assertThat(((Number) totals.get(0)[2]).longValue()).isEqualTo(1L);
        assertThat(((Number) totals.get(1)[0]).longValue()).isEqualTo(1L);
        assertThat((BigDecimal) totals.get(1)[1]).isEqualByComparingTo("2.00");
        assertThat(((Number) totals.get(1)[2]).longValue()).isEqualTo(2L);
        assertThat(((Number) totals.get(2)[0]).longValue()).isEqualTo(2L);
        assertThat((BigDecimal) totals.get(2)[1]).isEqualByComparingTo("0.10");
    }

    @Test
    void findByCreatedAtGreaterThanEqual_returnsOnlyRowsInWindow() {
        Instant now = Instant.now();
        ExpertReviewCost old = record(1L, "QA", 1, 1, 0, 0, "0.10");
        old.setCreatedAt(now.minusSeconds(7200));
        costRepository.save(old);
        ExpertReviewCost recent = record(2L, "QA", 1, 1, 0, 0, "0.20");
        recent.setCreatedAt(now.minusSeconds(60));
        costRepository.save(recent);

        List<ExpertReviewCost> rows = costRepository
                .findByCreatedAtGreaterThanEqualOrderByCreatedAtAsc(now.minusSeconds(3600));

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getSuggestionId()).isEqualTo(2L);
    }

    @Test
    void findTop20ByOrderByCreatedAtDesc_capsAndOrdersByNewestFirst() {
        Instant base = Instant.now().minusSeconds(60_000);
        for (int i = 0; i < 25; i++) {
            ExpertReviewCost r = record((long) i, "QA", 1, 1, 0, 0, "0.01");
            r.setCreatedAt(base.plusSeconds(i));
            costRepository.save(r);
        }

        List<ExpertReviewCost> rows = costRepository.findTop20ByOrderByCreatedAtDesc();

        assertThat(rows).hasSize(20);
        // Newest first: the last saved row (suggestion 24) is at the top.
        assertThat(rows.get(0).getSuggestionId()).isEqualTo(24L);
        assertThat(rows.get(rows.size() - 1).getSuggestionId()).isEqualTo(5L);
    }
}
