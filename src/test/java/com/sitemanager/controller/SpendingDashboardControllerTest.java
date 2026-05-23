package com.sitemanager.controller;

import com.sitemanager.dto.GlobalCapStatusDto;
import com.sitemanager.dto.SpendingDashboardDto;
import com.sitemanager.model.enums.CostResetPeriod;
import com.sitemanager.model.enums.Permission;
import com.sitemanager.service.PermissionService;
import com.sitemanager.service.SpendingDashboardService;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpSession;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link SpendingDashboardController}: the role gate, the default
 * vs. caller-supplied window sizes, and the path through to the service.
 */
class SpendingDashboardControllerTest {

    private SpendingDashboardService dashboardService;
    private PermissionService permissionService;
    private SpendingDashboardController controller;
    private MockHttpSession session;

    @BeforeEach
    void setUp() {
        dashboardService = mock(SpendingDashboardService.class);
        permissionService = mock(PermissionService.class);
        controller = new SpendingDashboardController(dashboardService, permissionService);
        session = new MockHttpSession();
        when(dashboardService.buildDashboard(anyInt(), anyInt()))
                .thenReturn(sampleDashboard());
    }

    @Test
    void get_returnsForbiddenWhenCallerLacksManageSettings() {
        when(permissionService.hasPermission(any(HttpSession.class),
                eq(Permission.MANAGE_SETTINGS))).thenReturn(false);

        ResponseEntity<?> response = controller.get(null, null, session);

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        assertThat(response.getBody()).isInstanceOf(Map.class);
        assertThat((Map<?, ?>) response.getBody()).containsKey("error");
        verify(dashboardService, never()).buildDashboard(anyInt(), anyInt());
    }

    @Test
    void get_returnsDashboardWhenAdmin() {
        when(permissionService.hasPermission(any(HttpSession.class),
                eq(Permission.MANAGE_SETTINGS))).thenReturn(true);

        ResponseEntity<?> response = controller.get(null, null, session);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isInstanceOf(SpendingDashboardDto.class);
    }

    @Test
    void get_defaultsWindowSizeWhenNotProvided() {
        when(permissionService.hasPermission(any(HttpSession.class),
                eq(Permission.MANAGE_SETTINGS))).thenReturn(true);

        controller.get(null, null, session);

        verify(dashboardService).buildDashboard(
                SpendingDashboardService.DEFAULT_TREND_WINDOW_DAYS,
                SpendingDashboardService.DEFAULT_TOP_SUGGESTIONS);
    }

    @Test
    void get_passesQueryParametersThroughToService() {
        when(permissionService.hasPermission(any(HttpSession.class),
                eq(Permission.MANAGE_SETTINGS))).thenReturn(true);

        controller.get(7, 3, session);

        ArgumentCaptor<Integer> trend = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<Integer> top = ArgumentCaptor.forClass(Integer.class);
        verify(dashboardService).buildDashboard(trend.capture(), top.capture());
        assertThat(trend.getValue()).isEqualTo(7);
        assertThat(top.getValue()).isEqualTo(3);
    }

    private SpendingDashboardDto sampleDashboard() {
        GlobalCapStatusDto cap = GlobalCapStatusDto.unlimited(
                java.math.BigDecimal.ZERO,
                CostResetPeriod.NEVER,
                Instant.EPOCH, null);
        return new SpendingDashboardDto(cap, List.of(), List.of(), List.of(),
                SpendingDashboardService.DEFAULT_TREND_WINDOW_DAYS, Instant.now());
    }
}
