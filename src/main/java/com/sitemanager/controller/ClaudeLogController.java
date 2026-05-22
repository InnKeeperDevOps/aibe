package com.sitemanager.controller;

import com.sitemanager.model.ClaudeCliLog;
import com.sitemanager.model.enums.Permission;
import com.sitemanager.repository.ClaudeCliLogRepository;
import com.sitemanager.service.PermissionService;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Admin-only endpoints for browsing the Claude CLI log: every prompt sent to
 * the CLI and the raw output it returned. Gated on {@link Permission#MANAGE_SETTINGS}.
 */
@RestController
@RequestMapping("/api/claude-logs")
public class ClaudeLogController {

    private static final int PROMPT_PREVIEW_LENGTH = 160;

    private final ClaudeCliLogRepository cliLogRepository;
    private final PermissionService permissionService;

    public ClaudeLogController(ClaudeCliLogRepository cliLogRepository,
                               PermissionService permissionService) {
        this.cliLogRepository = cliLogRepository;
        this.permissionService = permissionService;
    }

    /** Recent CLI invocations as lightweight summaries (no full prompt/output). */
    @GetMapping
    public ResponseEntity<?> list(HttpSession session) {
        if (!permissionService.hasPermission(session, Permission.MANAGE_SETTINGS)) {
            return ResponseEntity.status(403).body(Map.of("error", "Admin access required"));
        }
        List<Map<String, Object>> summaries = new ArrayList<>();
        for (ClaudeCliLog log : cliLogRepository.findTop500ByOrderByCreatedAtDesc()) {
            Map<String, Object> m = new java.util.HashMap<>();
            m.put("id", log.getId());
            m.put("requestId", log.getRequestId());
            m.put("operationType", log.getOperationType());
            m.put("model", log.getModel());
            m.put("exitCode", log.getExitCode());
            m.put("durationMs", log.getDurationMs());
            m.put("createdAt", log.getCreatedAt());
            m.put("promptPreview", preview(log.getPrompt()));
            m.put("failed", log.getExitCode() == null || log.getExitCode() != 0);
            summaries.add(m);
        }
        return ResponseEntity.ok(summaries);
    }

    /** A single CLI invocation with the full prompt, command, and raw output. */
    @GetMapping("/{id}")
    public ResponseEntity<?> detail(@PathVariable Long id, HttpSession session) {
        if (!permissionService.hasPermission(session, Permission.MANAGE_SETTINGS)) {
            return ResponseEntity.status(403).body(Map.of("error", "Admin access required"));
        }
        return cliLogRepository.findById(id)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(404).body(Map.of("error", "Log entry not found")));
    }

    private static String preview(String s) {
        if (s == null) return "";
        String oneLine = s.replaceAll("\\s+", " ").trim();
        return oneLine.length() > PROMPT_PREVIEW_LENGTH
                ? oneLine.substring(0, PROMPT_PREVIEW_LENGTH) + "…"
                : oneLine;
    }
}
