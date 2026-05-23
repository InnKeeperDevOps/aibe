package com.sitemanager.controller;

import com.sitemanager.dto.CostSummaryDto;
import com.sitemanager.service.CostRollupService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link CostRollupController}: read endpoints return the totals
 * computed by {@link CostRollupService}.
 */
class CostRollupControllerTest {

    private CostRollupService rollupService;
    private CostRollupController controller;

    @BeforeEach
    void setUp() {
        rollupService = mock(CostRollupService.class);
        controller = new CostRollupController(rollupService);
    }

    @Test
    void getSuggestionCosts_returnsServiceSummary() {
        CostSummaryDto summary = new CostSummaryDto(5L, 3L, 1000L, new BigDecimal("0.75"));
        when(rollupService.getSuggestionSummary(5L)).thenReturn(summary);

        ResponseEntity<CostSummaryDto> response = controller.getSuggestionCosts(5L);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isSameAs(summary);
    }

    @Test
    void getGlobalCosts_returnsServiceSummary() {
        CostSummaryDto summary = new CostSummaryDto(null, 10L, 99_999L, new BigDecimal("12.34"));
        when(rollupService.getGlobalSummary()).thenReturn(summary);

        ResponseEntity<CostSummaryDto> response = controller.getGlobalCosts();

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isSameAs(summary);
    }
}
