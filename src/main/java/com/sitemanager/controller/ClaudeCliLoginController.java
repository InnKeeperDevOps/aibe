package com.sitemanager.controller;

import com.sitemanager.model.enums.Permission;
import com.sitemanager.service.ClaudeCliLoginService;
import com.sitemanager.service.PermissionService;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/claude-cli-login")
public class ClaudeCliLoginController {

    private final ClaudeCliLoginService loginService;
    private final PermissionService permissionService;

    public ClaudeCliLoginController(ClaudeCliLoginService loginService,
                                    PermissionService permissionService) {
        this.loginService = loginService;
        this.permissionService = permissionService;
    }

    @GetMapping("/status")
    public ResponseEntity<?> status(HttpSession session) {
        if (!permissionService.hasPermission(session, Permission.MANAGE_SETTINGS)) {
            return ResponseEntity.status(403).body(Map.of("error", "Admin access required"));
        }
        return ResponseEntity.ok(loginService.getStatusSnapshot());
    }

    @PostMapping("/start")
    public ResponseEntity<?> start(HttpSession session) {
        if (!permissionService.hasPermission(session, Permission.MANAGE_SETTINGS)) {
            return ResponseEntity.status(403).body(Map.of("error", "Admin access required"));
        }
        return ResponseEntity.ok(loginService.start());
    }

    @PostMapping("/code")
    public ResponseEntity<?> submitCode(@RequestBody Map<String, String> body, HttpSession session) {
        if (!permissionService.hasPermission(session, Permission.MANAGE_SETTINGS)) {
            return ResponseEntity.status(403).body(Map.of("error", "Admin access required"));
        }
        return ResponseEntity.ok(loginService.submitCode(body.getOrDefault("code", "")));
    }

    @PostMapping("/cancel")
    public ResponseEntity<?> cancel(HttpSession session) {
        if (!permissionService.hasPermission(session, Permission.MANAGE_SETTINGS)) {
            return ResponseEntity.status(403).body(Map.of("error", "Admin access required"));
        }
        return ResponseEntity.ok(loginService.cancel());
    }

    @DeleteMapping("/credentials")
    public ResponseEntity<?> clear(HttpSession session) {
        if (!permissionService.hasPermission(session, Permission.MANAGE_SETTINGS)) {
            return ResponseEntity.status(403).body(Map.of("error", "Admin access required"));
        }
        return ResponseEntity.ok(loginService.clearStoredCredentials());
    }
}
