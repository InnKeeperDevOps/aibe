package com.sitemanager.controller;

import com.sitemanager.dto.SpendingDashboardDto;
import com.sitemanager.model.enums.Permission;
import com.sitemanager.service.PermissionService;
import com.sitemanager.service.SpendingDashboardService;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Read-only endpoint for the admin spending dashboard. Returns the live
 * status of the global cap, the top-spending suggestions, a per-day trend
 * over a recent window, and a feed of the most recent recorded reviews.
 *
 * <p>Gated on {@link Permission#MANAGE_SETTINGS} — the dashboard exposes
 * detail (suggestion ids, costs, expert names) that should not be
 * accessible to non-admin users. The role check runs on every request so
 * permission changes take effect immediately.
 */
@RestController
@RequestMapping("/api/spending-dashboard")
public class SpendingDashboardController {

    private final SpendingDashboardService dashboardService;
    private final PermissionService permissionService;

    public SpendingDashboardController(SpendingDashboardService dashboardService,
                                       PermissionService permissionService) {
        this.dashboardService = dashboardService;
        this.permissionService = permissionService;
    }

    /**
     * Build and return the dashboard. The trend window and top-N size are
     * tunable via query parameters but always clamped by the service.
     */
    @GetMapping
    public ResponseEntity<?> get(@RequestParam(name = "trendDays", required = false) Integer trendDays,
                                 @RequestParam(name = "topN", required = false) Integer topN,
                                 HttpSession session) {
        if (!permissionService.hasPermission(session, Permission.MANAGE_SETTINGS)) {
            return ResponseEntity.status(403).body(Map.of("error", "Admin access required"));
        }
        int days = trendDays != null
                ? trendDays : SpendingDashboardService.DEFAULT_TREND_WINDOW_DAYS;
        int top = topN != null
                ? topN : SpendingDashboardService.DEFAULT_TOP_SUGGESTIONS;
        SpendingDashboardDto dashboard = dashboardService.buildDashboard(days, top);
        return ResponseEntity.ok(dashboard);
    }
}
