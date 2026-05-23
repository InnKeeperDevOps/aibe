package com.sitemanager.dto;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link CostSummaryDto}: defaults, getter/setter pairs, and the
 * human-readable {@code displayCostUsd} that dashboards render.
 */
class CostSummaryDtoTest {

    @Test
    void defaultConstructor_initialisesCostsToZeroAndProvidesDisplayString() {
        CostSummaryDto dto = new CostSummaryDto();
        assertThat(dto.getTotalCostUsd()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(dto.getReviewCount()).isZero();
        assertThat(dto.getTotalTokens()).isZero();
        assertThat(dto.getDisplayCostUsd()).isEqualTo("$0.0000");
        assertThat(dto.getSuggestionId()).isNull();
    }

    @Test
    void allArgsConstructor_populatesEverythingAndFormatsDisplay() {
        CostSummaryDto dto = new CostSummaryDto(11L, 4, 12_345L, new BigDecimal("0.5"));
        assertThat(dto.getSuggestionId()).isEqualTo(11L);
        assertThat(dto.getReviewCount()).isEqualTo(4L);
        assertThat(dto.getTotalTokens()).isEqualTo(12_345L);
        assertThat(dto.getTotalCostUsd()).isEqualByComparingTo(new BigDecimal("0.5"));
        assertThat(dto.getDisplayCostUsd()).isEqualTo("$0.5000");
    }

    @Test
    void nullCost_normalisedToZero() {
        CostSummaryDto dto = new CostSummaryDto(1L, 0, 0, null);
        assertThat(dto.getTotalCostUsd()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(dto.getDisplayCostUsd()).isEqualTo("$0.0000");
    }

    @Test
    void setTotalCostUsd_updatesDisplayString() {
        CostSummaryDto dto = new CostSummaryDto();
        dto.setTotalCostUsd(new BigDecimal("1.23456789"));
        assertThat(dto.getDisplayCostUsd()).isEqualTo("$1.2346");
    }

    @Test
    void setTotalCostUsd_nullResetsToZero() {
        CostSummaryDto dto = new CostSummaryDto(null, 0, 0, new BigDecimal("5"));
        dto.setTotalCostUsd(null);
        assertThat(dto.getTotalCostUsd()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(dto.getDisplayCostUsd()).isEqualTo("$0.0000");
    }

    @Test
    void settersStoreValues() {
        CostSummaryDto dto = new CostSummaryDto();
        dto.setSuggestionId(42L);
        dto.setReviewCount(7L);
        dto.setTotalTokens(99L);
        dto.setDisplayCostUsd("$X");
        assertThat(dto.getSuggestionId()).isEqualTo(42L);
        assertThat(dto.getReviewCount()).isEqualTo(7L);
        assertThat(dto.getTotalTokens()).isEqualTo(99L);
        assertThat(dto.getDisplayCostUsd()).isEqualTo("$X");
    }
}
