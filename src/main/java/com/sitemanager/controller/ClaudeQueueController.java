package com.sitemanager.controller;

import com.sitemanager.model.enums.Permission;
import com.sitemanager.service.ClaudeService;
import com.sitemanager.service.PermissionService;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Admin-only endpoint for the live Claude CLI request queue: rate-limit and
 * concurrency limits, current window usage, and every in-flight request with
 * its phase ({@code AWAITING_RATE_LIMIT}, {@code AWAITING_CONCURRENCY},
 * {@code RUNNING}). Gated on {@link Permission#MANAGE_SETTINGS}.
 */
@RestController
@RequestMapping("/api/claude-queue")
public class ClaudeQueueController {

    private final ClaudeService claudeService;
    private final PermissionService permissionService;

    public ClaudeQueueController(ClaudeService claudeService,
                                 PermissionService permissionService) {
        this.claudeService = claudeService;
        this.permissionService = permissionService;
    }

    @GetMapping
    public ResponseEntity<?> snapshot(HttpSession session) {
        if (!permissionService.hasPermission(session, Permission.MANAGE_SETTINGS)) {
            return ResponseEntity.status(403).body(Map.of("error", "Admin access required"));
        }
        return ResponseEntity.ok(claudeService.getClaudeQueueSnapshot());
    }
}
