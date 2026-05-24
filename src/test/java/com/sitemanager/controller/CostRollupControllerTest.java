package com.sitemanager.controller;

import com.sitemanager.dto.CostSummaryDto;
import com.sitemanager.model.enums.Permission;
import com.sitemanager.service.CostRollupService;
import com.sitemanager.service.PermissionService;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link CostRollupController}: read endpoints return the totals
 * computed by {@link CostRollupService} when the caller is an admin, and 403
 * otherwise.
 */
class CostRollupControllerTest {

    private CostRollupService rollupService;
    private PermissionService permissionService;
    private HttpSession session;
    private CostRollupController controller;

    @BeforeEach
    void setUp() {
        rollupService = mock(CostRollupService.class);
        permissionService = mock(PermissionService.class);
        session = mock(HttpSession.class);
        controller = new CostRollupController(rollupService, permissionService);
    }

    @Test
    void getSuggestionCosts_returnsServiceSummary_forAdmin() {
        when(permissionService.hasPermission(session, Permission.MANAGE_SETTINGS)).thenReturn(true);
        CostSummaryDto summary = new CostSummaryDto(5L, 3L, 1000L, new BigDecimal("0.75"));
        when(rollupService.getSuggestionSummary(5L)).thenReturn(summary);

        ResponseEntity<?> response = controller.getSuggestionCosts(5L, session);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isSameAs(summary);
    }

    @Test
    void getSuggestionCosts_returns403_forNonAdmin() {
        when(permissionService.hasPermission(session, Permission.MANAGE_SETTINGS)).thenReturn(false);

        ResponseEntity<?> response = controller.getSuggestionCosts(5L, session);

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        assertThat(response.getBody()).isEqualTo(Map.of("error", "Admin access required"));
    }

    @Test
    void getGlobalCosts_returnsServiceSummary_forAdmin() {
        when(permissionService.hasPermission(session, Permission.MANAGE_SETTINGS)).thenReturn(true);
        CostSummaryDto summary = new CostSummaryDto(null, 10L, 99_999L, new BigDecimal("12.34"));
        when(rollupService.getGlobalSummary()).thenReturn(summary);

        ResponseEntity<?> response = controller.getGlobalCosts(session);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isSameAs(summary);
    }

    @Test
    void getGlobalCosts_returns403_forNonAdmin() {
        when(permissionService.hasPermission(session, Permission.MANAGE_SETTINGS)).thenReturn(false);

        ResponseEntity<?> response = controller.getGlobalCosts(session);

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        assertThat(response.getBody()).isEqualTo(Map.of("error", "Admin access required"));
    }
}
