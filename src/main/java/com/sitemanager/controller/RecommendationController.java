package com.sitemanager.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitemanager.model.RecommendationResult;
import com.sitemanager.model.RecommendationRun;
import com.sitemanager.model.SiteSettings;
import com.sitemanager.model.Suggestion;
import com.sitemanager.model.enums.Permission;
import com.sitemanager.model.enums.RecommendationResultStatus;
import com.sitemanager.model.enums.RecommendationRunStatus;
import com.sitemanager.model.enums.SuggestionStatus;
import com.sitemanager.repository.RecommendationResultRepository;
import com.sitemanager.repository.RecommendationRunRepository;
import com.sitemanager.repository.SuggestionRepository;
import com.sitemanager.service.ClaudeService;
import com.sitemanager.service.PermissionService;
import com.sitemanager.service.SiteSettingsService;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/recommendations")
public class RecommendationController {

    private static final Logger log = LoggerFactory.getLogger(RecommendationController.class);

    private final PermissionService permissionService;
    private final SiteSettingsService settingsService;
    private final SuggestionRepository suggestionRepository;
    private final ClaudeService claudeService;
    private final ObjectMapper objectMapper;
    private final RecommendationRunRepository runRepository;
    private final RecommendationResultRepository resultRepository;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    public RecommendationController(PermissionService permissionService,
                                    SiteSettingsService settingsService,
                                    SuggestionRepository suggestionRepository,
                                    ClaudeService claudeService,
                                    ObjectMapper objectMapper,
                                    RecommendationRunRepository runRepository,
                                    RecommendationResultRepository resultRepository) {
        this.permissionService = permissionService;
        this.settingsService = settingsService;
        this.suggestionRepository = suggestionRepository;
        this.claudeService = claudeService;
        this.objectMapper = objectMapper;
        this.runRepository = runRepository;
        this.resultRepository = resultRepository;
    }

    @PostMapping
    public ResponseEntity<?> getRecommendations(HttpSession session) {
        if (!permissionService.hasPermission(session, Permission.MANAGE_SETTINGS)) {
            return ResponseEntity.status(403).body(Map.of("error", "Admin access required"));
        }
        String taskId = startNewRun(session);
        return ResponseEntity.accepted().body(Map.of("taskId", taskId));
    }

    /**
     * Re-run a past recommendation run on demand. The original run's results are
     * left untouched; a brand-new {@link RecommendationRun} is created with a
     * fresh taskId and enqueued. The new run is attributed to whoever clicks the
     * button (not the original requester), since the analysis runs against the
     * current state of the repository for that user.
     */
    @PostMapping("/runs/{taskId}/rerun")
    public ResponseEntity<?> rerunRun(@PathVariable String taskId, HttpSession session) {
        if (!permissionService.hasPermission(session, Permission.MANAGE_SETTINGS)) {
            return ResponseEntity.status(403).body(Map.of("error", "Admin access required"));
        }
        if (runRepository.findByTaskId(taskId).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        String newTaskId = startNewRun(session);
        return ResponseEntity.accepted().body(Map.of("taskId", newTaskId));
    }

    /**
     * Mark one recommendation as having been turned into a tracked suggestion.
     * The row is never deleted — instead its status flips to ACTED_ON and the
     * linked suggestion id is recorded — so the active list stops showing it
     * while history keeps the full record. Idempotent: re-marking an already
     * acted-on recommendation updates the linked suggestion id without error.
     */
    @PostMapping("/results/{id}/act-on")
    public ResponseEntity<?> markResultActedOn(@PathVariable Long id,
                                                @RequestBody Map<String, Object> body,
                                                HttpSession session) {
        if (!permissionService.hasPermission(session, Permission.MANAGE_SETTINGS)) {
            return ResponseEntity.status(403).body(Map.of("error", "Admin access required"));
        }
        Optional<RecommendationResult> maybeResult = resultRepository.findById(id);
        if (maybeResult.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        Long suggestionId = parseSuggestionId(body);
        if (suggestionId == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "suggestionId is required"));
        }
        if (suggestionRepository.findById(suggestionId).isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Unknown suggestion"));
        }
        RecommendationResult result = maybeResult.get();
        result.setStatus(RecommendationResultStatus.ACTED_ON);
        result.setActedOnSuggestionId(suggestionId);
        resultRepository.save(result);
        return ResponseEntity.ok(Map.of(
                "id", result.getId(),
                "status", result.getStatus().name(),
                "actedOnSuggestionId", result.getActedOnSuggestionId()));
    }

    private Long parseSuggestionId(Map<String, Object> body) {
        if (body == null) return null;
        Object raw = body.get("suggestionId");
        if (raw instanceof Number) return ((Number) raw).longValue();
        if (raw instanceof String) {
            try {
                return Long.parseLong(((String) raw).trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /**
     * Create a new persisted {@link RecommendationRun} attributed to the
     * current session, save it, and submit it to the background executor.
     * Returns the freshly generated taskId so callers can poll for status.
     */
    private String startNewRun(HttpSession session) {
        String taskId = UUID.randomUUID().toString();

        RecommendationRun run = new RecommendationRun();
        run.setTaskId(taskId);
        run.setStatus(RecommendationRunStatus.IN_PROGRESS);
        Object userIdAttr = session.getAttribute("userId");
        if (userIdAttr instanceof Long) {
            run.setRequestedByUserId((Long) userIdAttr);
        } else if (userIdAttr instanceof Number) {
            run.setRequestedByUserId(((Number) userIdAttr).longValue());
        }
        Object usernameAttr = session.getAttribute("username");
        if (usernameAttr instanceof String) {
            run.setRequestedByUsername((String) usernameAttr);
        }
        runRepository.save(run);

        enqueueRun(run);
        return taskId;
    }

    /**
     * Submit a saved {@link RecommendationRun} to the background executor.
     * Used both by the POST endpoint and by the startup resume hook that
     * picks up runs interrupted by a restart.
     */
    public void enqueueRun(RecommendationRun run) {
        String taskId = run.getTaskId();
        SiteSettings settings = settingsService.getSettings();
        List<Suggestion> suggestions = suggestionRepository.findAllByOrderByCreatedAtDesc();
        Map<SuggestionStatus, Long> statusCounts = suggestions.stream()
                .collect(Collectors.groupingBy(Suggestion::getStatus, Collectors.counting()));
        int total = suggestions.size();

        executor.submit(() -> {
            try {
                ensureMainRepoAvailable(settings);
                String projectDefinition = readProjectDefinition();
                String prompt = buildPrompt(settings, statusCounts, total, projectDefinition);
                String rawResponse = claudeService.getRecommendations(prompt);
                List<Map<String, String>> recommendations = parseRecommendations(rawResponse);
                persistSuccess(taskId, recommendations);
            } catch (IllegalStateException e) {
                log.warn("[RECOMMENDATIONS] Failed to parse response: {}", e.getMessage());
                persistFailure(taskId, "The AI returned an unexpected response. Please try again.");
            } catch (RuntimeException e) {
                if (e.getMessage() != null && e.getMessage().contains("timed out")) {
                    log.warn("[RECOMMENDATIONS] Request timed out: {}", e.getMessage());
                    persistFailure(taskId, "The AI took too long to respond. Please try again in a moment.");
                } else {
                    log.error("[RECOMMENDATIONS] Unexpected error: {}", e.getMessage(), e);
                    persistFailure(taskId, "Unable to get recommendations right now. Please try again.");
                }
            } catch (Exception e) {
                log.error("[RECOMMENDATIONS] Unexpected error: {}", e.getMessage(), e);
                persistFailure(taskId, "Unable to get recommendations right now. Please try again.");
            }
        });
    }

    @GetMapping("/status/{taskId}")
    public ResponseEntity<?> getStatus(@PathVariable String taskId) {
        Optional<RecommendationRun> maybeRun = runRepository.findByTaskId(taskId);
        if (maybeRun.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        RecommendationRun run = maybeRun.get();
        RecommendationRunStatus status = run.getStatus();
        if (status == RecommendationRunStatus.PENDING || status == RecommendationRunStatus.IN_PROGRESS) {
            return ResponseEntity.ok(Map.of("status", "pending"));
        }
        if (status == RecommendationRunStatus.ERROR) {
            String error = run.getErrorMessage() != null ? run.getErrorMessage() : "";
            return ResponseEntity.ok(Map.of("status", "error", "error", error));
        }
        // The "active" list excludes recommendations that have already been
        // turned into tracked suggestions — they're kept in history but should
        // not clutter the working list the admin sees right now.
        List<RecommendationResult> rows = resultRepository.findByRunIdAndStatusOrderByResultOrderAsc(
                run.getId(), RecommendationResultStatus.PENDING);
        List<Map<String, Object>> data = rows.stream()
                .map(this::toActiveResultMap)
                .collect(Collectors.toList());
        return ResponseEntity.ok(Map.of("status", "done", "data", data));
    }

    private Map<String, Object> toActiveResultMap(RecommendationResult r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId());
        m.put("title", r.getTitle() != null ? r.getTitle() : "");
        m.put("description", r.getDescription() != null ? r.getDescription() : "");
        return m;
    }

    /**
     * List past recommendation runs, newest first, for the history view.
     * All filters are optional — when omitted the full history is returned.
     * Filters supported:
     *   - status: one of PENDING / IN_PROGRESS / DONE / ERROR (case-insensitive)
     *   - from:   ISO-8601 instant; only runs created at or after this time are returned
     *   - to:     ISO-8601 instant; only runs created at or before this time are returned
     *
     * An invalid status value or unparseable date returns 400 so the UI can show
     * a clear error rather than silently dropping the filter.
     */
    @GetMapping("/runs")
    public ResponseEntity<?> listRuns(HttpSession session,
                                       @RequestParam(value = "status", required = false) String statusParam,
                                       @RequestParam(value = "from", required = false) String fromParam,
                                       @RequestParam(value = "to", required = false) String toParam) {
        if (!permissionService.hasPermission(session, Permission.MANAGE_SETTINGS)) {
            return ResponseEntity.status(403).body(Map.of("error", "Admin access required"));
        }

        RecommendationRunStatus status;
        try {
            status = parseStatus(statusParam);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }

        Instant from;
        Instant to;
        try {
            from = parseInstant(fromParam, "from");
            to = parseInstant(toParam, "to");
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }

        List<RecommendationRun> runs = (status == null && from == null && to == null)
                ? runRepository.findAllByOrderByCreatedAtDesc()
                : runRepository.findFiltered(status, from, to);
        List<Map<String, Object>> summaries = runs.stream()
                .map(this::toRunSummary)
                .collect(Collectors.toList());
        return ResponseEntity.ok(Map.of("runs", summaries));
    }

    static RecommendationRunStatus parseStatus(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return RecommendationRunStatus.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown status filter: " + raw);
        }
    }

    static Instant parseInstant(String raw, String fieldName) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return Instant.parse(raw.trim());
        } catch (java.time.format.DateTimeParseException e) {
            throw new IllegalArgumentException("Invalid " + fieldName + " date — expected ISO-8601 instant");
        }
    }

    /**
     * Fetch the full details of one past recommendation run: status, when it
     * started and finished, any error message, and the bullets that were
     * produced.
     */
    @GetMapping("/runs/{taskId}")
    public ResponseEntity<?> getRunDetail(@PathVariable String taskId, HttpSession session) {
        if (!permissionService.hasPermission(session, Permission.MANAGE_SETTINGS)) {
            return ResponseEntity.status(403).body(Map.of("error", "Admin access required"));
        }

        Optional<RecommendationRun> maybeRun = runRepository.findByTaskId(taskId);
        if (maybeRun.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        RecommendationRun run = maybeRun.get();

        // History keeps the full record — including bullets that have been
        // turned into tracked suggestions — so admins can still browse and
        // see which item led to which suggestion.
        List<RecommendationResult> rows = resultRepository.findByRunIdOrderByResultOrderAsc(run.getId());
        List<Map<String, Object>> recommendations = rows.stream()
                .map(this::toHistoryResultMap)
                .collect(Collectors.toList());

        Map<String, Object> body = new LinkedHashMap<>(toRunSummary(run));
        body.put("recommendations", recommendations);
        return ResponseEntity.ok(body);
    }

    private Map<String, Object> toHistoryResultMap(RecommendationResult r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId());
        m.put("title", r.getTitle() != null ? r.getTitle() : "");
        m.put("description", r.getDescription() != null ? r.getDescription() : "");
        m.put("status", r.getStatus() != null ? r.getStatus().name() : RecommendationResultStatus.PENDING.name());
        m.put("actedOnSuggestionId", r.getActedOnSuggestionId());
        return m;
    }

    private Map<String, Object> toRunSummary(RecommendationRun run) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("taskId", run.getTaskId());
        summary.put("status", run.getStatus() != null ? run.getStatus().name() : null);
        summary.put("createdAt", run.getCreatedAt() != null ? run.getCreatedAt().toString() : null);
        summary.put("completedAt", run.getCompletedAt() != null ? run.getCompletedAt().toString() : null);
        summary.put("requestedByUsername", run.getRequestedByUsername());
        summary.put("errorMessage", run.getErrorMessage());
        Long count = resultRepository.countByRunId(run.getId());
        summary.put("resultCount", count != null ? count : 0L);
        return summary;
    }

    private void persistSuccess(String taskId, List<Map<String, String>> recommendations) {
        RecommendationRun run = runRepository.findByTaskId(taskId).orElse(null);
        if (run == null) {
            log.warn("[RECOMMENDATIONS] Could not find run {} to mark as done", taskId);
            return;
        }
        // Clear any partial results from a previous attempt for safety.
        resultRepository.deleteByRunId(run.getId());

        int order = 0;
        for (Map<String, String> rec : recommendations) {
            RecommendationResult row = new RecommendationResult(
                    run.getId(),
                    order++,
                    rec.getOrDefault("title", ""),
                    rec.getOrDefault("description", ""));
            resultRepository.save(row);
        }
        run.setStatus(RecommendationRunStatus.DONE);
        run.setErrorMessage(null);
        run.setCompletedAt(Instant.now());
        runRepository.save(run);
    }

    private void persistFailure(String taskId, String errorMessage) {
        RecommendationRun run = runRepository.findByTaskId(taskId).orElse(null);
        if (run == null) {
            log.warn("[RECOMMENDATIONS] Could not find run {} to mark as failed", taskId);
            return;
        }
        run.setStatus(RecommendationRunStatus.ERROR);
        run.setErrorMessage(errorMessage);
        run.setCompletedAt(Instant.now());
        runRepository.save(run);
    }

    /**
     * Pull or clone the main repository so Claude has up-to-date source to analyze.
     */
    private void ensureMainRepoAvailable(SiteSettings settings) {
        String repoUrl = settings.getTargetRepoUrl();
        if (repoUrl == null || repoUrl.isBlank()) {
            return;
        }
        try {
            claudeService.pullMainRepository(repoUrl);
        } catch (Exception e) {
            log.warn("[RECOMMENDATIONS] Could not update main-repo (will use existing state): {}", e.getMessage());
        }
    }

    /**
     * Read PROJECT_DEFINITION.md from the main-repo directory, if it exists.
     */
    String readProjectDefinition() {
        try {
            java.nio.file.Path filePath = Paths.get(claudeService.getMainRepoDir(), "PROJECT_DEFINITION.md");
            return Files.readString(filePath);
        } catch (java.nio.file.NoSuchFileException e) {
            return null;
        } catch (Exception e) {
            log.warn("[RECOMMENDATIONS] Could not read PROJECT_DEFINITION.md: {}", e.getMessage());
            return null;
        }
    }

    String buildPrompt(SiteSettings settings, Map<SuggestionStatus, Long> statusCounts,
                        int total, String projectDefinition) {
        StringBuilder sb = new StringBuilder();

        sb.append("You are a technical advisor analyzing a software project to identify gaps between ");
        sb.append("the intended vision and the current implementation.\n\n");

        sb.append("Project: ")
                .append(settings.getSiteName() != null ? settings.getSiteName() : "Software Project")
                .append("\n");
        if (settings.getTargetRepoUrl() != null && !settings.getTargetRepoUrl().isBlank()) {
            sb.append("Repository: ").append(settings.getTargetRepoUrl()).append("\n");
        }

        if (projectDefinition != null && !projectDefinition.isBlank()) {
            sb.append("\n=== PROJECT DEFINITION (the intended vision) ===\n");
            sb.append(projectDefinition);
            sb.append("\n=== END PROJECT DEFINITION ===\n");
        }

        sb.append("\nCurrent suggestion statistics (total: ").append(total).append("):\n");
        statusCounts.forEach((status, count) ->
                sb.append("  ").append(status.name()).append(": ").append(count).append("\n"));

        sb.append("\nINSTRUCTIONS:\n");
        sb.append("You are running in the project's repository directory. ");
        sb.append("Explore the codebase — read key files, understand the architecture, ");
        sb.append("and examine what has been implemented so far.\n\n");

        if (projectDefinition != null && !projectDefinition.isBlank()) {
            sb.append("Compare the PROJECT DEFINITION above against the ACTUAL implementation in the repository. ");
            sb.append("Identify the most important gaps — features described in the definition that are ");
            sb.append("missing, incomplete, or differ significantly from what was envisioned.\n\n");
            sb.append("Focus on:\n");
            sb.append("- Features or capabilities described in the definition that are not yet implemented\n");
            sb.append("- Features that are partially implemented but missing key aspects\n");
            sb.append("- Architectural or design discrepancies between vision and reality\n");
            sb.append("- Quality gaps (e.g. missing tests, error handling, performance concerns mentioned in the definition)\n\n");
        } else {
            sb.append("There is no PROJECT_DEFINITION.md yet. Analyze the codebase and suggest the most ");
            sb.append("impactful improvements based on what you find in the code.\n\n");
            sb.append("Focus on:\n");
            sb.append("- Missing features that would make the project more complete\n");
            sb.append("- Code quality improvements (tests, error handling, documentation)\n");
            sb.append("- Architectural improvements\n");
            sb.append("- User experience enhancements\n\n");
        }

        sb.append("Suggest exactly 5 concrete, actionable improvements ranked by impact.\n\n");
        sb.append("Respond ONLY with a valid JSON array of exactly 5 objects. ");
        sb.append("Each object must have a \"title\" (short, under 100 characters) and a \"description\" (1-2 sentences). ");
        sb.append("Do not include any text before or after the JSON array.\n");
        sb.append("Example: [{\"title\": \"Add email notifications\", ");
        sb.append("\"description\": \"The project definition calls for email alerts on status changes, but no notification system exists in the codebase.\"}]");
        return sb.toString();
    }

    List<Map<String, String>> parseRecommendations(String rawResponse) {
        if (rawResponse == null || rawResponse.isBlank()) {
            throw new IllegalStateException("Empty response from AI");
        }
        try {
            int start = rawResponse.indexOf('[');
            int end = rawResponse.lastIndexOf(']');
            if (start < 0 || end <= start) {
                throw new IllegalArgumentException("No JSON array found in response");
            }
            String jsonArray = rawResponse.substring(start, end + 1);
            JsonNode arr = objectMapper.readTree(jsonArray);
            if (!arr.isArray() || arr.size() == 0) {
                throw new IllegalArgumentException("Empty or non-array JSON in response");
            }
            List<Map<String, String>> result = new ArrayList<>();
            for (JsonNode node : arr) {
                String title = node.path("title").asText("").trim();
                String description = node.path("description").asText("").trim();
                if (!title.isEmpty()) {
                    result.add(Map.of("title", title, "description", description));
                }
            }
            if (result.isEmpty()) {
                throw new IllegalArgumentException("No valid recommendations in parsed response");
            }
            return result;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Malformed response from AI: " + e.getMessage(), e);
        }
    }
}
