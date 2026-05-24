package com.sitemanager.controller;

import com.sitemanager.dto.CostSummaryDto;
import com.sitemanager.model.enums.Permission;
import com.sitemanager.service.CostRollupService;
import com.sitemanager.service.PermissionService;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Read-only endpoints for cost roll-ups. Lets the UI fetch the current
 * total for one suggestion or the system-wide total on demand, separately
 * from the real-time WebSocket push. Admin-only — token usage and dollar
 * spend leaks operational cost info the suggestion authors don't need.
 */
@RestController
@RequestMapping("/api/costs")
public class CostRollupController {

    private final CostRollupService rollupService;
    private final PermissionService permissionService;

    public CostRollupController(CostRollupService rollupService, PermissionService permissionService) {
        this.rollupService = rollupService;
        this.permissionService = permissionService;
    }

    @GetMapping("/suggestion/{id}")
    public ResponseEntity<?> getSuggestionCosts(@PathVariable Long id, HttpSession session) {
        if (!permissionService.hasPermission(session, Permission.MANAGE_SETTINGS)) {
            return ResponseEntity.status(403).body(Map.of("error", "Admin access required"));
        }
        return ResponseEntity.ok(rollupService.getSuggestionSummary(id));
    }

    @GetMapping("/global")
    public ResponseEntity<?> getGlobalCosts(HttpSession session) {
        if (!permissionService.hasPermission(session, Permission.MANAGE_SETTINGS)) {
            return ResponseEntity.status(403).body(Map.of("error", "Admin access required"));
        }
        return ResponseEntity.ok(rollupService.getGlobalSummary());
    }
}
