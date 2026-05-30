package com.sitemanager.controller;

import com.sitemanager.dto.UserSummaryDto;
import com.sitemanager.model.enums.Permission;
import com.sitemanager.service.PermissionService;
import com.sitemanager.service.UserService;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserService userService;
    private final PermissionService permissionService;

    public UserController(UserService userService, PermissionService permissionService) {
        this.userService = userService;
        this.permissionService = permissionService;
    }

    @GetMapping
    public ResponseEntity<?> listUsers(HttpSession session) {
        if (!permissionService.hasPermission(session, Permission.MANAGE_USERS)) {
            return ResponseEntity.status(403).body(Map.of("error", "Access denied"));
        }
        List<UserSummaryDto> users = userService.findAll().stream()
                .map(UserSummaryDto::from)
                .collect(Collectors.toList());
        return ResponseEntity.ok(users);
    }

    @GetMapping("/pending")
    public ResponseEntity<?> listPendingUsers(HttpSession session) {
        if (!permissionService.hasPermission(session, Permission.MANAGE_USERS)) {
            return ResponseEntity.status(403).body(Map.of("error", "Access denied"));
        }
        List<UserSummaryDto> users = userService.findPending().stream()
                .map(UserSummaryDto::from)
                .collect(Collectors.toList());
        return ResponseEntity.ok(users);
    }

    @PostMapping("/{id}/approve")
    public ResponseEntity<?> approveUser(@PathVariable Long id, HttpSession session) {
        if (!permissionService.hasPermission(session, Permission.MANAGE_USERS)) {
            return ResponseEntity.status(403).body(Map.of("error", "Access denied"));
        }
        try {
            return ResponseEntity.ok(UserSummaryDto.from(userService.approve(id)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/{id}/deny")
    public ResponseEntity<?> denyUser(@PathVariable Long id, HttpSession session) {
        if (!permissionService.hasPermission(session, Permission.MANAGE_USERS)) {
            return ResponseEntity.status(403).body(Map.of("error", "Access denied"));
        }
        try {
            return ResponseEntity.ok(UserSummaryDto.from(userService.deny(id)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PutMapping("/{id}/group")
    public ResponseEntity<?> assignGroup(@PathVariable Long id, @RequestBody Map<String, Long> body,
                                         HttpSession session) {
        if (!permissionService.hasPermission(session, Permission.MANAGE_USERS)) {
            return ResponseEntity.status(403).body(Map.of("error", "Access denied"));
        }
        Long groupId = body.get("groupId");
        if (groupId == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "groupId is required"));
        }
        try {
            return ResponseEntity.ok(UserSummaryDto.from(userService.assignGroup(id, groupId)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
