package com.sitemanager.model;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the {@link ExpertReviewCost} entity: defaults, getters/setters,
 * the lifecycle {@code @PrePersist} hook, and the {@code totalTokens} roll-up.
 */
class ExpertReviewCostTest {

    @Test
    void newRecord_defaultsCostUsdToZero() {
        ExpertReviewCost record = new ExpertReviewCost();
        assertThat(record.getCostUsd()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void settersStoreValues() {
        ExpertReviewCost record = new ExpertReviewCost();
        record.setSuggestionId(42L);
        record.setExpertName("QA Engineer");
        record.setOperationType("expert-review:QA Engineer");
        record.setReviewSessionId("session-1");
        record.setModel("claude-opus");
        record.setInputTokens(100);
        record.setOutputTokens(50);
        record.setCacheReadInputTokens(200);
        record.setCacheCreationInputTokens(25);
        record.setCostUsd(new BigDecimal("1.234567"));
        record.setDurationMs(987L);

        assertThat(record.getSuggestionId()).isEqualTo(42L);
        assertThat(record.getExpertName()).isEqualTo("QA Engineer");
        assertThat(record.getOperationType()).isEqualTo("expert-review:QA Engineer");
        assertThat(record.getReviewSessionId()).isEqualTo("session-1");
        assertThat(record.getModel()).isEqualTo("claude-opus");
        assertThat(record.getInputTokens()).isEqualTo(100);
        assertThat(record.getOutputTokens()).isEqualTo(50);
        assertThat(record.getCacheReadInputTokens()).isEqualTo(200);
        assertThat(record.getCacheCreationInputTokens()).isEqualTo(25);
        assertThat(record.getCostUsd()).isEqualByComparingTo(new BigDecimal("1.234567"));
        assertThat(record.getDurationMs()).isEqualTo(987L);
    }

    @Test
    void setCostUsd_nullIsNormalizedToZero() {
        ExpertReviewCost record = new ExpertReviewCost();
        record.setCostUsd(new BigDecimal("5.00"));
        record.setCostUsd(null);
        assertThat(record.getCostUsd()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void totalTokens_includesAllTokenStreams() {
        ExpertReviewCost record = new ExpertReviewCost();
        record.setInputTokens(10);
        record.setOutputTokens(20);
        record.setCacheReadInputTokens(30);
        record.setCacheCreationInputTokens(40);
        assertThat(record.getTotalTokens()).isEqualTo(100);
    }

    @Test
    void prePersist_populatesCreatedAtAndKeepsCostNonNull() throws Exception {
        ExpertReviewCost record = new ExpertReviewCost();
        assertThat(record.getCreatedAt()).isNull();

        Method onCreate = ExpertReviewCost.class.getDeclaredMethod("onCreate");
        onCreate.setAccessible(true);
        onCreate.invoke(record);

        assertThat(record.getCreatedAt()).isNotNull();
        assertThat(record.getCreatedAt()).isBeforeOrEqualTo(Instant.now());
        assertThat(record.getCostUsd()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void prePersist_doesNotOverwriteExistingCreatedAt() throws Exception {
        ExpertReviewCost record = new ExpertReviewCost();
        Instant fixed = Instant.parse("2025-01-01T00:00:00Z");
        record.setCreatedAt(fixed);

        Method onCreate = ExpertReviewCost.class.getDeclaredMethod("onCreate");
        onCreate.setAccessible(true);
        onCreate.invoke(record);

        assertThat(record.getCreatedAt()).isEqualTo(fixed);
    }
}
