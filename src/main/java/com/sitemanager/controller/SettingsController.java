package com.sitemanager.controller;

import com.sitemanager.model.SiteSettings;
import com.sitemanager.model.enums.Permission;
import com.sitemanager.service.PermissionService;
import com.sitemanager.service.SiteSettingsService;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/settings")
public class SettingsController {

    private final SiteSettingsService settingsService;
    private final PermissionService permissionService;

    public SettingsController(SiteSettingsService settingsService, PermissionService permissionService) {
        this.settingsService = settingsService;
        this.permissionService = permissionService;
    }

    @GetMapping
    public ResponseEntity<SiteSettings> get() {
        return ResponseEntity.ok(settingsService.getSettings());
    }

    @PutMapping
    public ResponseEntity<?> update(@RequestBody SiteSettings settings, HttpSession session) {
        if (!permissionService.hasPermission(session, Permission.MANAGE_SETTINGS)) {
            return ResponseEntity.status(403).body(Map.of("error", "Admin access required"));
        }
        return ResponseEntity.ok(settingsService.updateSettings(settings));
    }

    @PostMapping("/git-ssh-key/generate")
    public ResponseEntity<?> generateGitSshKey(HttpSession session) {
        if (!permissionService.hasPermission(session, Permission.MANAGE_SETTINGS)) {
            return ResponseEntity.status(403).body(Map.of("error", "Admin access required"));
        }
        return ResponseEntity.ok(settingsService.generateSshKeyPair());
    }
}
