package com.sitemanager.controller;

import com.sitemanager.dto.AuthStatusResponse;
import com.sitemanager.dto.ChangePasswordRequest;
import com.sitemanager.dto.LoginRequest;
import com.sitemanager.dto.RegisterRequest;
import com.sitemanager.dto.SetupRequest;
import com.sitemanager.exception.AccountDeniedException;
import com.sitemanager.exception.AccountPendingApprovalException;
import com.sitemanager.model.User;
import com.sitemanager.model.enums.Permission;
import com.sitemanager.service.AuthService;
import com.sitemanager.service.PermissionService;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final PermissionService permissionService;

    public AuthController(AuthService authService, PermissionService permissionService) {
        this.authService = authService;
        this.permissionService = permissionService;
    }

    @GetMapping("/status")
    public ResponseEntity<AuthStatusResponse> status(HttpSession session) {
        boolean setupRequired = authService.isSetupRequired();
        String currentUser = (String) session.getAttribute("username");
        String role = (String) session.getAttribute("role");

        List<String> permissions = Arrays.stream(Permission.values())
                .filter(p -> permissionService.hasPermission(session, p))
                .map(Permission::name)
                .collect(Collectors.toList());

        return ResponseEntity.ok(new AuthStatusResponse(
                setupRequired,
                currentUser != null,
                currentUser != null ? currentUser : "",
                role != null ? role : "",
                permissions
        ));
    }

    @PostMapping("/setup")
    public ResponseEntity<?> setup(@Valid @RequestBody SetupRequest request, HttpSession session) {
        if (!authService.isSetupRequired()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Root admin already exists"));
        }

        User admin = authService.setupRootAdmin(request);
        session.setAttribute("username", admin.getUsername());
        session.setAttribute("role", admin.getRole().name());
        session.setAttribute("userId", admin.getId());

        return ResponseEntity.ok(Map.of(
                "message", "Root admin created successfully",
                "username", admin.getUsername(),
                "role", admin.getRole().name()
        ));
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest request, HttpSession session) {
        try {
            return authService.authenticate(request)
                    .map(user -> {
                        session.setAttribute("username", user.getUsername());
                        session.setAttribute("role", user.getRole().name());
                        session.setAttribute("userId", user.getId());
                        return ResponseEntity.ok(Map.of(
                                "message", "Login successful",
                                "username", user.getUsername(),
                                "role", user.getRole().name()
                        ));
                    })
                    .orElse(ResponseEntity.status(401).body(Map.of("error", "Invalid credentials")));
        } catch (AccountPendingApprovalException | AccountDeniedException e) {
            return ResponseEntity.status(401).body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/logout")
    public ResponseEntity<?> logout(HttpSession session) {
        session.invalidate();
        return ResponseEntity.ok(Map.of("message", "Logged out"));
    }

    @PostMapping("/change-password")
    public ResponseEntity<?> changePassword(@Valid @RequestBody ChangePasswordRequest request,
                                            HttpSession session) {
        String currentUser = (String) session.getAttribute("username");
        if (currentUser == null) {
            return ResponseEntity.status(401).body(Map.of("error", "You must be logged in to change your password"));
        }
        try {
            authService.changePassword(currentUser, request.getCurrentPassword(), request.getNewPassword());
            return ResponseEntity.ok(Map.of("message", "Password changed successfully"));
        } catch (ResponseStatusException e) {
            return ResponseEntity.status(e.getStatusCode()).body(Map.of("error", e.getReason()));
        }
    }

    @PostMapping("/create-admin")
    public ResponseEntity<?> createAdmin(@Valid @RequestBody SetupRequest request, HttpSession session) {
        String role = (String) session.getAttribute("role");
        if (!"ROOT_ADMIN".equals(role)) {
            return ResponseEntity.status(403).body(Map.of("error", "Only root admin can create admins"));
        }
        try {
            User admin = authService.createAdmin(request.getUsername(), request.getPassword());
            return ResponseEntity.ok(Map.of(
                    "message", "Admin created",
                    "username", admin.getUsername()
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/register")
    public ResponseEntity<?> register(@Valid @RequestBody RegisterRequest request) {
        try {
            User user = authService.register(request);
            boolean pending = !user.isApproved();
            String message = pending
                    ? "Registration submitted and pending admin approval"
                    : "Registration successful";
            return ResponseEntity.ok(Map.of(
                    "message", message,
                    "username", user.getUsername(),
                    "pending", pending
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        } catch (ResponseStatusException e) {
            return ResponseEntity.status(e.getStatusCode()).body(Map.of("error", e.getReason()));
        }
    }
}
