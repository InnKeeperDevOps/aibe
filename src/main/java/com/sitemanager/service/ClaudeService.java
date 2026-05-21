package com.sitemanager.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sitemanager.model.SiteSettings;
import com.sitemanager.model.enums.ExpertRole;
import com.sitemanager.repository.SiteSettingsRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

@Service
public class ClaudeService {

    private static final Logger log = LoggerFactory.getLogger(ClaudeService.class);
    private static final int MAX_LOG_PROMPT_LENGTH = 500;
    private static final int MAX_LOG_RESPONSE_LENGTH = 1000;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final Map<String, String> sessionMap = new ConcurrentHashMap<>();
    private final AtomicLong requestCounter = new AtomicLong(0);

    // FIFO rate limiter: fair ReentrantLock guarantees threads acquire in arrival order
    private long[] callTimestamps;
    private int timestampHead = 0;
    private final java.util.concurrent.locks.ReentrantLock rateLimitLock =
            new java.util.concurrent.locks.ReentrantLock(true); // fair = FIFO

    // Concurrency gate: fair semaphore limits parallel CLI processes in FIFO order
    private final Semaphore claudeGate;

    private final SiteSettingsRepository settingsRepository;

    @Value("${app.claude-cli-path:claude}")
    private String claudeCliPath;

    @Value("${app.workspace-dir:/workspace}")
    private String workspaceDir;

    @Value("${app.git-ssh-key-path:}")
    private String gitSshKeyPath;

    @Value("${app.claude-timeout-minutes:30}")
    private int claudeTimeoutMinutes;

    @Value("${app.claude-verbose:false}")
    private boolean claudeVerbose;

    @Value("${app.claude-run-as-user:}")
    private String claudeRunAsUser;

    @Value("${app.claude-model:}")
    private String claudeModelDefault;

    @Value("${app.claude-model-expert:}")
    private String claudeModelExpertDefault;

    @Value("${app.claude-max-turns-expert:6}")
    private int claudeMaxTurnsExpertDefault;

    @Value("${app.claude-max-concurrent:2}")
    private int claudeMaxConcurrent;

    @Value("${app.claude-max-calls-per-minute:10}")
    private int claudeMaxCallsPerMinute;

    @Value("${app.claude-max-retries:3}")
    private int claudeMaxRetries;

    @Value("${app.claude-retry-base-delay-ms:2000}")
    private long claudeRetryBaseDelayMs;

    @Value("${app.claude-retry-max-delay-ms:30000}")
    private long claudeRetryMaxDelayMs;

    public ClaudeService(SiteSettingsRepository settingsRepository) {
        this.settingsRepository = settingsRepository;
        // Defaults; @PostConstruct re-inits with configured values
        this.claudeGate = new Semaphore(2, true); // fair = FIFO ordering
        this.callTimestamps = new long[10];
    }

    @jakarta.annotation.PostConstruct
    void initRateLimiter() {
        // Re-initialize semaphore with configured concurrency limit
        claudeGate.drainPermits();
        claudeGate.release(claudeMaxConcurrent);
        // Re-size timestamp buffer to match configured rate limit
        this.callTimestamps = new long[claudeMaxCallsPerMinute];
        this.timestampHead = 0;
        log.info("Claude CLI rate limiter initialized: maxConcurrent={}, maxCallsPerMinute={}, fifo=true",
                claudeMaxConcurrent, claudeMaxCallsPerMinute);
        if (shouldRunAsDifferentUser()) {
            ensureUserExists(claudeRunAsUser);
            log.info("Claude CLI subprocesses will run as user '{}' (current user is root)", claudeRunAsUser);
        }
        resolveClaudeCliPath();
        materializeStoredClaudeCredentials();
        materializeStoredClaudeConfig();
    }

    /**
     * If a Claude CLI credentials blob is stored in site_settings, write it to
     * the run-as-user's ~/.claude/.credentials.json so Claude CLI subprocesses
     * can authenticate without an interactive login.
     */
    public void materializeStoredClaudeCredentials() {
        try {
            SiteSettings settings = settingsRepository.findAll().stream().findFirst().orElse(null);
            if (settings == null || !settings.hasClaudeCredentials()) {
                return;
            }
            String credsJson = settings.getClaudeCredentials();
            Path credsFile = Path.of(getClaudeCliHome(), ".claude", ".credentials.json");
            Files.createDirectories(credsFile.getParent());
            Files.writeString(credsFile, credsJson, StandardCharsets.UTF_8);
            try {
                Set<PosixFilePermission> perms = PosixFilePermissions.fromString("rw-------");
                Files.setPosixFilePermissions(credsFile, perms);
            } catch (Exception ignored) {
                // non-POSIX filesystem — best effort
            }
            chownToRunAsUser(credsFile);
            log.info("Materialized stored Claude credentials to {}", credsFile);
        } catch (Exception e) {
            log.warn("Failed to materialize stored Claude credentials: {}", e.getMessage());
        }
    }

    /**
     * Write the Claude CLI config (theme, onboarding flag, …) stored in
     * site_settings out to {@code ~/.claude.json} so the CLI picks it up on
     * the next invocation. Seeds the row with {@link SiteSettings#DEFAULT_CLAUDE_CONFIG}
     * on first install so a brand-new container starts in light mode with the
     * onboarding wizard suppressed.
     */
    public void materializeStoredClaudeConfig() {
        try {
            SiteSettings settings = settingsRepository.findAll().stream()
                    .findFirst()
                    .orElseGet(() -> settingsRepository.save(new SiteSettings()));
            String configJson = settings.getClaudeConfig();
            if (configJson == null || configJson.isBlank()) {
                configJson = SiteSettings.DEFAULT_CLAUDE_CONFIG;
                settings.setClaudeConfig(configJson);
                settingsRepository.save(settings);
            }
            Path configFile = Path.of(getClaudeCliHome(), ".claude.json");
            Files.createDirectories(configFile.getParent());
            Files.writeString(configFile, configJson, StandardCharsets.UTF_8);
            chownToRunAsUser(configFile);
            log.info("Materialized Claude CLI config to {}", configFile);
            markDirectoryTrusted(workspaceDir);
        } catch (Exception e) {
            log.warn("Failed to materialize Claude CLI config: {}", e.getMessage());
        }
    }

    private void chownToRunAsUser(Path path) {
        if (!shouldRunAsDifferentUser()) {
            return;
        }
        try {
            ProcessBuilder chown = new ProcessBuilder(
                    "chown", claudeRunAsUser + ":" + claudeRunAsUser, path.toString());
            chown.redirectErrorStream(true);
            Process p = chown.start();
            p.waitFor();
        } catch (Exception e) {
            log.debug("chown {} -> {} failed: {}", path, claudeRunAsUser, e.getMessage());
        }
    }

    /**
     * Resolve {@link #claudeCliPath} to an absolute path so ProcessBuilder does not
     * depend on the JVM's PATH environment to locate the binary. If the configured
     * value already contains a path separator, it is kept as-is. Otherwise we try
     * {@code which <name>} on the configured value, falling back to common npm
     * global install locations. Logs a warning if the binary cannot be found, but
     * leaves the configured value untouched so callers still get a clear error.
     */
    private void resolveClaudeCliPath() {
        if (claudeCliPath == null || claudeCliPath.isBlank()) {
            return;
        }
        if (claudeCliPath.contains("/")) {
            return;
        }
        try {
            ProcessBuilder pb = new ProcessBuilder("which", claudeCliPath);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            int exit = p.waitFor();
            if (exit == 0 && !output.isBlank()) {
                String resolved = output.split("\\R")[0].trim();
                if (new File(resolved).exists()) {
                    log.info("Resolved Claude CLI '{}' to absolute path: {}", claudeCliPath, resolved);
                    claudeCliPath = resolved;
                    return;
                }
            }
        } catch (Exception e) {
            log.debug("`which {}` failed: {}", claudeCliPath, e.getMessage());
        }
        String[] candidates = {
                "/usr/local/bin/" + claudeCliPath,
                "/usr/bin/" + claudeCliPath,
                "/opt/homebrew/bin/" + claudeCliPath,
                "/root/.npm-global/bin/" + claudeCliPath,
                "/usr/lib/node_modules/@anthropic-ai/claude-code/cli.js"
        };
        for (String candidate : candidates) {
            if (new File(candidate).exists()) {
                log.info("Located Claude CLI at {}", candidate);
                claudeCliPath = candidate;
                return;
            }
        }
        log.warn("Could not resolve absolute path for Claude CLI '{}' — relying on PATH lookup at exec time",
                claudeCliPath);
    }

    /**
     * Check whether subprocesses should be run as a different user.
     * This is true when running as root and claude-run-as-user is configured.
     */
    private boolean shouldRunAsDifferentUser() {
        return claudeRunAsUser != null && !claudeRunAsUser.isBlank()
                && "root".equals(System.getProperty("user.name"));
    }

    /**
     * Ensure the given OS user exists, creating it if necessary.
     * Also ensures the workspace directory is accessible to the user.
     */
    private void ensureUserExists(String username) {
        try {
            // Check if user already exists via `id`
            ProcessBuilder checkPb = new ProcessBuilder("id", "-u", username);
            checkPb.redirectErrorStream(true);
            Process checkProcess = checkPb.start();
            checkProcess.getInputStream().readAllBytes();
            int exitCode = checkProcess.waitFor();
            if (exitCode == 0) {
                log.info("OS user '{}' already exists", username);
            } else {
                log.info("OS user '{}' does not exist, creating it", username);
                ProcessBuilder createPb = new ProcessBuilder("useradd", "-m", "-s", "/bin/bash", username);
                createPb.redirectErrorStream(true);
                Process createProcess = createPb.start();
                String output = new String(createProcess.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
                int createExit = createProcess.waitFor();
                if (createExit != 0) {
                    log.error("Failed to create user '{}': {}", username, output);
                    return;
                }
                log.info("Created OS user '{}'", username);
            }
            // Ensure the workspace directory is accessible to the user
            File workspace = new File(workspaceDir);
            if (workspace.exists()) {
                ProcessBuilder chownPb = new ProcessBuilder("chown", "-R", username + ":" + username, workspaceDir);
                chownPb.redirectErrorStream(true);
                Process chownProcess = chownPb.start();
                chownProcess.getInputStream().readAllBytes();
                chownProcess.waitFor();
                log.info("Set ownership of {} to {}", workspaceDir, username);
            }
        } catch (Exception e) {
            log.error("Failed to ensure user '{}' exists: {}", username, e.getMessage(), e);
        }
    }

    /**
     * Wrap a command list with "runuser -u &lt;user&gt; --" so it executes as the
     * configured non-root user. Returns the original command unchanged if not running as root
     * or no run-as user is configured.
     */
    private List<String> wrapCommandForUser(List<String> command) {
        if (!shouldRunAsDifferentUser()) {
            return command;
        }
        List<String> wrapped = new java.util.ArrayList<>();
        wrapped.add("runuser");
        wrapped.add("-u");
        wrapped.add(claudeRunAsUser);
        wrapped.add("--");
        wrapped.addAll(command);
        return wrapped;
    }

    /**
     * Acquire a rate limit slot using a FIFO fair lock.
     * Threads queue in arrival order. If the sliding window is full (N calls in last 60s),
     * the calling thread sleeps for exactly the time needed until the oldest call expires,
     * while holding the fair lock so all other callers wait behind it in FIFO order.
     */
    void acquireRateLimit() throws InterruptedException {
        rateLimitLock.lockInterruptibly(); // FIFO: threads queue in arrival order
        try {
            int windowSize = callTimestamps.length;
            long windowMs = 60_000L;
            int slot = timestampHead % windowSize;
            long oldest = callTimestamps[slot];
            if (oldest > 0) {
                long elapsed = System.currentTimeMillis() - oldest;
                if (elapsed < windowMs) {
                    long sleepMs = windowMs - elapsed + 50; // +50ms buffer
                    log.info("[CLAUDE-RATE-LIMIT] FIFO queue: {} calls in last 60s, sleeping {}ms",
                            windowSize, sleepMs);
                    Thread.sleep(sleepMs);
                }
            }
            // Record this call's timestamp in the circular buffer
            callTimestamps[slot] = System.currentTimeMillis();
            timestampHead++;
        } finally {
            rateLimitLock.unlock();
        }
    }

    private SiteSettings getSettings() {
        return settingsRepository.findAll().stream().findFirst().orElse(new SiteSettings());
    }

    /**
     * Resolve the model to use for main operations.
     * Settings DB takes priority, then application.yml / env var, then CLI default.
     */
    private String resolveModel() {
        try {
            String fromSettings = getSettings().getClaudeModel();
            if (fromSettings != null && !fromSettings.isBlank()) return fromSettings;
        } catch (Exception e) {
            log.debug("Could not read model from settings: {}", e.getMessage());
        }
        return claudeModelDefault;
    }

    /**
     * Resolve the model to use for expert reviews.
     * Settings DB takes priority, then application.yml / env var, then falls back to main model.
     */
    private String resolveExpertModel() {
        try {
            String fromSettings = getSettings().getClaudeModelExpert();
            if (fromSettings != null && !fromSettings.isBlank()) return fromSettings;
        } catch (Exception e) {
            log.debug("Could not read expert model from settings: {}", e.getMessage());
        }
        if (claudeModelExpertDefault != null && !claudeModelExpertDefault.isBlank()) return claudeModelExpertDefault;
        return resolveModel();
    }

    /**
     * Resolve max turns for expert reviews.
     * Settings DB takes priority, then application.yml / env var default.
     */
    private int resolveExpertMaxTurns() {
        try {
            Integer fromSettings = getSettings().getClaudeMaxTurnsExpert();
            if (fromSettings != null && fromSettings > 0) return fromSettings;
        } catch (Exception e) {
            log.debug("Could not read expert max turns from settings: {}", e.getMessage());
        }
        return claudeMaxTurnsExpertDefault;
    }

    public String generateSessionId() {
        return UUID.randomUUID().toString();
    }

    public String getMainRepoDir() {
        return workspaceDir + "/main-repo";
    }

    /**
     * Get AI recommendations by having Claude analyze the project repository.
     * Runs in {@code main-repo/} so Claude can read source files and compare
     * the project definition against the actual implementation.
     */
    public String getRecommendations(String prompt) throws Exception {
        String sessionId = generateSessionId();
        String model = resolveModel();
        return sendToClaudeGated(prompt, sessionId, getMainRepoDir(), null, null, "recommendations", model, 0);
    }

    /**
     * Create a new branch from the current HEAD in the given repo directory.
     */
    public void createBranch(String repoDir, String branchName) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(wrapCommandForUser(
                java.util.List.of("git", "checkout", "-b", branchName)));
        pb.directory(new File(repoDir));
        pb.redirectErrorStream(true);
        pb.redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")));
        Process process = pb.start();

        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
                log.info("Git checkout -b: {}", line);
            }
        }

        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new RuntimeException("Failed to create branch " + branchName + ": " + output.toString().trim());
        }
        log.info("Created branch {} in {}", branchName, repoDir);
    }

    public CompletableFuture<String> evaluateSuggestion(String suggestionTitle, String suggestionDescription,
                                                         String repoUrl, String sessionId,
                                                         String workingDir,
                                                         Consumer<String> progressCallback) {
        String prompt = buildEvaluationPrompt(suggestionTitle, suggestionDescription, repoUrl);
        return sendToClaudeAsync(prompt, sessionId, workingDir, null, progressCallback, "evaluate", resolveModel(), 0);
    }

    String buildEvaluationPrompt(String suggestionTitle, String suggestionDescription, String repoUrl) {
        return String.format(
                "You are reviewing a suggestion for a website or application at: %s\n\n" +
                "Suggestion Title: %s\n" +
                "Suggestion Description: %s\n\n" +
                "MANDATORY RULE: You MUST always respond with status \"NEEDS_CLARIFICATION\" on this initial evaluation. " +
                "Do NOT respond with \"PLAN_READY\" at this stage, no matter how detailed the suggestion appears. " +
                "A \"PLAN_READY\" response is only allowed after the user has answered your clarifying questions.\n\n" +
                "Your job right now is to ask 2-4 plain-language questions that help you understand:\n" +
                "- What outcome or result the user is hoping for\n" +
                "- Who will use this feature and how they expect it to work\n" +
                "- What the priority or scope is (e.g. simple version first, or full-featured from the start)\n" +
                "- Any constraints, preferences, or things that should or should not change\n\n" +
                "Keep questions focused on goals and desired behavior, not technical details. " +
                "Write questions in plain, everyday language that any non-technical user can understand.\n\n" +
                "Respond using this exact JSON format:\n" +
                "{\"status\": \"NEEDS_CLARIFICATION\", " +
                "\"message\": \"brief, friendly explanation of why you are asking these questions\", " +
                "\"questions\": [\"question 1\", \"question 2\", ...]}\n\n" +
                "IMPORTANT: You MUST include a \"questions\" array with 2-4 items. Each question must be a plain-language string " +
                "focused on outcomes, priorities, scope, or constraints — never on technical implementation details. " +
                "Do NOT include a \"plan\" or \"tasks\" field. Do NOT respond with status \"PLAN_READY\".",
                repoUrl != null ? repoUrl : "not configured",
                suggestionTitle,
                suggestionDescription
        );
    }

    public CompletableFuture<String> continueConversation(String sessionId, String userMessage,
                                                           String conversationContext,
                                                           String workingDir,
                                                           Consumer<String> progressCallback) {
        return sendToClaudeAsync(userMessage, sessionId, workingDir, conversationContext, progressCallback, "continue", resolveModel(), 0);
    }

    public CompletableFuture<String> executePlan(String sessionId, String plan, String tasksJson,
                                                  String workingDir,
                                                  Consumer<String> progressCallback) {
        String prompt = String.format(
                "Execute the following implementation plan in the repository at %s.\n\n" +
                "Plan:\n%s\n\n" +
                "Tasks (execute in order):\n%s\n\n" +
                "COMMUNICATION RULES:\n" +
                "- All message fields in your JSON output MUST be written in plain, non-technical language.\n" +
                "- NEVER mention programming languages, frameworks, libraries, file names, class names, or technical details in messages.\n" +
                "- Describe progress in terms of what is changing from the user's perspective.\n" +
                "  Good: \"Added the new option to the settings page\"\n" +
                "  Bad: \"Created SiteSettings.java field and REST endpoint\"\n\n" +
                "Instructions:\n" +
                "1. Execute each task in order by its task number\n" +
                "2. Write unit tests for all new code\n" +
                "3. Run existing tests to ensure nothing is broken\n\n" +
                "For EACH task, follow this workflow:\n\n" +
                "Step A — Start the task:\n" +
                "{\"taskOrder\": number, \"status\": \"IN_PROGRESS\", \"message\": \"starting description\"}\n\n" +
                "Step B — Implement the task (write code, make changes)\n\n" +
                "Step C — Review the task. After implementing, review your own code changes for this task:\n" +
                "  - Verify the code changes actually fulfill the task requirements\n" +
                "  - Check for bugs, missing edge cases, or incomplete implementation\n" +
                "  - Run any relevant tests to confirm correctness\n" +
                "Output a review status:\n" +
                "{\"taskOrder\": number, \"status\": \"REVIEWING\", \"message\": \"reviewing: what you checked\"}\n\n" +
                "Step D — If the review passes, mark the task completed:\n" +
                "{\"taskOrder\": number, \"status\": \"COMPLETED\", \"message\": \"what was done and verified\"}\n" +
                "If the review finds issues, fix them and re-review before marking completed.\n\n" +
                "Step E — If a task cannot be completed, mark it failed:\n" +
                "{\"taskOrder\": number, \"status\": \"FAILED\", \"message\": \"what went wrong\"}\n\n" +
                "After ALL tasks pass review, output a final summary:\n" +
                "{\"status\": \"COMPLETED\", \"message\": \"summary\", \"testsRun\": number, \"testsPassed\": number}\n" +
                "If the overall execution fails:\n" +
                "{\"status\": \"FAILED\", \"message\": \"what went wrong\"}",
                workingDir, plan, tasksJson != null ? tasksJson : "No structured tasks — follow the plan above."
        );

        return sendToClaudeAsync(prompt, sessionId, workingDir, null, progressCallback, "execute", resolveModel(), 0);
    }

    /**
     * Execute a single task from the plan. Called one task at a time so experts
     * can review each task's output before proceeding to the next.
     */
    public CompletableFuture<String> executeSingleTask(String sessionId, String plan,
                                                         int taskOrder, String taskTitle,
                                                         String taskDescription,
                                                         int totalTasks,
                                                         String completedTasksSummary,
                                                         String workingDir,
                                                         Consumer<String> progressCallback) {
        String prompt = String.format(
                "Execute ONLY task %d of %d in the repository at %s.\n\n" +
                "Overall Plan:\n%s\n\n" +
                "%s" +
                "YOUR TASK (Task %d of %d):\n" +
                "Title: %s\n" +
                "Description: %s\n\n" +
                "COMMUNICATION RULES:\n" +
                "- All message fields in your JSON output MUST be written in plain, non-technical language.\n" +
                "- NEVER mention programming languages, frameworks, libraries, file names, class names, or technical details in messages.\n" +
                "- Describe progress in terms of what is changing from the user's perspective.\n\n" +
                "Instructions:\n" +
                "1. Execute ONLY this single task — do NOT work on other tasks\n" +
                "2. Write unit tests for all new code in this task\n" +
                "3. Run existing tests to ensure nothing is broken\n\n" +
                "Follow this workflow:\n\n" +
                "Step A — Start the task:\n" +
                "{\"taskOrder\": %d, \"status\": \"IN_PROGRESS\", \"message\": \"starting description\"}\n\n" +
                "Step B — Implement the task (write code, make changes)\n\n" +
                "Step C — Review the task. After implementing, review your own code changes:\n" +
                "  - Verify the code changes actually fulfill the task requirements\n" +
                "  - Check for bugs, missing edge cases, or incomplete implementation\n" +
                "  - Run any relevant tests to confirm correctness\n" +
                "Output a review status:\n" +
                "{\"taskOrder\": %d, \"status\": \"REVIEWING\", \"message\": \"reviewing: what you checked\"}\n\n" +
                "Step D — If the review passes, mark the task completed:\n" +
                "{\"taskOrder\": %d, \"status\": \"COMPLETED\", \"message\": \"what was done and verified\"}\n" +
                "If the review finds issues, fix them and re-review before marking completed.\n\n" +
                "Step E — If the task cannot be completed, mark it failed:\n" +
                "{\"taskOrder\": %d, \"status\": \"FAILED\", \"message\": \"what went wrong\"}\n\n" +
                "IMPORTANT: Only work on task %d. Do NOT proceed to other tasks.",
                taskOrder, totalTasks, workingDir,
                plan,
                completedTasksSummary != null && !completedTasksSummary.isBlank() ?
                        "Previously completed tasks:\n" + completedTasksSummary + "\n\n" : "",
                taskOrder, totalTasks,
                taskTitle,
                taskDescription != null ? taskDescription : taskTitle,
                taskOrder, taskOrder, taskOrder, taskOrder, taskOrder
        );

        return sendToClaudeAsync(prompt, sessionId, workingDir, null, progressCallback,
                "execute-task-" + taskOrder, resolveModel(), 0);
    }

    /**
     * Have experts review a completed task's actual code changes to verify
     * the task was properly completed before moving to the next task.
     */
    public CompletableFuture<String> reviewTaskCompletion(String sessionId, String expertDisplayName,
                                                            String expertPrompt, String suggestionTitle,
                                                            int taskOrder, String taskTitle,
                                                            String taskDescription, String plan,
                                                            String workingDir,
                                                            Consumer<String> progressCallback) {
        String prompt = String.format(
                "%s\n\n" +
                "You are reviewing the ACTUAL CODE CHANGES made for a specific task.\n\n" +
                "Suggestion: %s\n" +
                "Overall Plan:\n%s\n\n" +
                "Task %d being reviewed:\n" +
                "Title: %s\n" +
                "Description: %s\n\n" +
                "INSTRUCTIONS:\n" +
                "1. Examine the current state of the code in the repository\n" +
                "2. Look at recent git changes (use git diff or git log) to see what was actually changed for this task\n" +
                "3. Verify the implementation matches the task requirements\n" +
                "4. Check for bugs, missing functionality, or incomplete work\n" +
                "5. Run tests if applicable to confirm correctness\n\n" +
                "REVIEW SEVERITY RULES:\n" +
                "- ONLY flag CRITICAL or MAJOR issues. Do NOT nitpick.\n" +
                "  CRITICAL: Would cause data loss, security vulnerabilities, system crashes, or broken core functionality.\n" +
                "  MAJOR: Significant bugs, missing key requirements, or incomplete implementation.\n" +
                "- If the task was completed reasonably well, APPROVE it. Most tasks should be approved.\n" +
                "- Bias toward action — a shipped improvement beats a perfect implementation.\n\n" +
                "Respond in this JSON format:\n" +
                "If the task is properly completed:\n" +
                "{\"status\": \"APPROVED\", \"analysis\": \"what you verified and why it passes\", " +
                "\"message\": \"concise NON-TECHNICAL summary for the user\"}\n\n" +
                "If the task has CRITICAL or MAJOR issues that must be fixed:\n" +
                "{\"status\": \"NEEDS_FIXES\", \"analysis\": \"what issues you found\", " +
                "\"fixes\": \"specific technical description of what needs to be fixed\", " +
                "\"message\": \"concise NON-TECHNICAL summary for the user\"}",
                expertPrompt,
                suggestionTitle,
                plan,
                taskOrder,
                taskTitle,
                taskDescription != null ? taskDescription : taskTitle
        );

        return sendToClaudeAsync(prompt, sessionId, workingDir, null, progressCallback,
                "task-review:" + expertDisplayName + ":task-" + taskOrder, resolveExpertModel(), resolveExpertMaxTurns());
    }

    public CompletableFuture<String> expertReview(String sessionId, String expertDisplayName,
                                                    String expertPrompt, String suggestionTitle,
                                                    String suggestionDescription, String plan,
                                                    String tasksJson, String previousNotes,
                                                    String workingDir,
                                                    Consumer<String> progressCallback,
                                                    int reviewRound,
                                                    List<Integer> ownerLockedSections) {
        boolean isProjectOwner = ExpertRole.PROJECT_OWNER.getDisplayName().equals(expertDisplayName);

        String prompt;
        if (isProjectOwner) {
            prompt = buildProjectOwnerReviewPrompt(expertPrompt, suggestionTitle, suggestionDescription,
                    plan, tasksJson, previousNotes);
        } else {
            prompt = buildStandardExpertReviewPrompt(expertPrompt, suggestionTitle, suggestionDescription,
                    plan, tasksJson, previousNotes, reviewRound, ownerLockedSections);
        }

        return sendToClaudeAsync(prompt, sessionId, workingDir, null, progressCallback,
                "expert-review:" + expertDisplayName, resolveExpertModel(), resolveExpertMaxTurns());
    }

    String buildProjectOwnerReviewPrompt(String expertPrompt, String suggestionTitle,
                                                   String suggestionDescription, String plan,
                                                   String tasksJson, String previousNotes) {
        return String.format(
                "%s\n\n" +
                "Suggestion Title: %s\n" +
                "Suggestion Description: %s\n\n" +
                "Current Plan:\n%s\n\n" +
                "%s" +
                "%s" +
                "DUAL-LEVEL DETAIL RULES:\n" +
                "- Your 'analysis' field may reference technical details about what is present or missing in the plan.\n" +
                "- Your 'message' field MUST be written in plain, non-technical language — describe features and behaviors only. " +
                "NEVER mention file names, classes, APIs, or technical implementation details in the message.\n\n" +
                "LOCKED TASK INDICES:\n" +
                "- In your response, include 'lockedTaskIndices': a list of 0-based indices of tasks you consider essential " +
                "to fulfilling the original request.\n" +
                "- These tasks will be protected from removal or scope changes by downstream reviewers.\n" +
                "- Include ALL tasks that directly implement what the user requested. " +
                "Only exclude tasks that are clearly optional or purely technical improvements.\n" +
                "- lockedTaskIndices is REQUIRED in all responses (use an empty list [] if no tasks exist yet).\n\n" +
                "Respond in this JSON format:\n" +
                "If the plan fully and faithfully captures the original suggestion:\n" +
                "{\"status\": \"APPROVED\", \"analysis\": \"what you verified and found to be complete\", " +
                "\"message\": \"concise NON-TECHNICAL summary for the user\", " +
                "\"lockedTaskIndices\": [0, 1, 2, ...]}\n\n" +
                "If the plan is missing content, reduces scope, or misrepresents the original request:\n" +
                "{\"status\": \"CHANGES_PROPOSED\", \"analysis\": \"what is missing or incorrect\", " +
                "\"proposedChanges\": \"technical description of what needs to be added or changed\", " +
                "\"revisedPlan\": \"updated low-level technical plan\", " +
                "\"revisedPlanDisplaySummary\": \"updated high-level non-technical summary for the user\", " +
                "\"revisedTasks\": [{\"title\": \"low-level technical task name\", \"description\": \"detailed technical description\", " +
                "\"displayTitle\": \"high-level user-facing task name\", \"displayDescription\": \"plain language description\", " +
                "\"estimatedMinutes\": number}, ...], " +
                "\"message\": \"concise NON-TECHNICAL summary for the user\", " +
                "\"lockedTaskIndices\": [0, 1, 2, ...]}\n\n" +
                "IMPORTANT: lockedTaskIndices is REQUIRED in every response. " +
                "List every 0-based task index that is essential to fulfilling the user's original request. " +
                "When proposing changes, include the COMPLETE revised task list, not just modified tasks.",
                expertPrompt,
                suggestionTitle,
                suggestionDescription,
                plan,
                tasksJson != null ? "Current Tasks:\n" + tasksJson + "\n\n" : "",
                previousNotes != null && !previousNotes.isBlank() ?
                        "Previous expert reviews:\n" + previousNotes + "\n\n" : ""
        );
    }

    String buildStandardExpertReviewPrompt(String expertPrompt, String suggestionTitle,
                                                     String suggestionDescription, String plan,
                                                     String tasksJson, String previousNotes,
                                                     int reviewRound, List<Integer> ownerLockedSections) {
        String roundContext = "";
        if (reviewRound > 1) {
            String ownerLockRoundNote = (ownerLockedSections != null && !ownerLockedSections.isEmpty())
                    ? "- Do NOT propose removing owner-locked tasks (indices: " + ownerLockedSections +
                      "). These are essential to the original request and cannot be removed.\n"
                    : "";
            roundContext = "ROUND " + reviewRound + " RE-REVIEW RULES (MANDATORY):\n" +
                "- This plan was ALREADY reviewed and updated. You are re-reviewing because changes " +
                "were made in a domain related to yours.\n" +
                "- The bar for proposing further changes is EXTREMELY HIGH. Only propose changes for:\n" +
                "  * CRITICAL issues introduced BY the recent changes (regressions)\n" +
                "  * CRITICAL issues that were clearly missed in round 1\n" +
                "- Do NOT re-raise issues noted by other experts in previous rounds.\n" +
                "- Do NOT propose changes for anything below CRITICAL severity.\n" +
                ownerLockRoundNote +
                "- If the plan is acceptable (even imperfect), you MUST approve it.\n" +
                "- The goal is CONVERGENCE, not perfection. Approve unless something is broken.\n\n";
        }

        String ownerLockContext = "";
        if (ownerLockedSections != null && !ownerLockedSections.isEmpty()) {
            ownerLockContext = "OWNER-LOCKED TASKS (DO NOT REMOVE OR CHANGE SCOPE):\n" +
                "The Project Owner has identified task indices " + ownerLockedSections +
                " as essential to fulfilling the original request.\n" +
                "When proposing revisedTasks, you MUST include all owner-locked tasks. " +
                "You may suggest different implementation approaches for these tasks, " +
                "but you CANNOT remove them or change what they deliver to the user.\n\n";
        }

        return String.format(
                "%s\n\n" +
                "%s" +
                "Suggestion Title: %s\n" +
                "Suggestion Description: %s\n\n" +
                "Current Plan:\n%s\n\n" +
                "%s" +
                "%s" +
                "%s" +
                "REVIEW SEVERITY RULES:\n" +
                "- ONLY flag CRITICAL, MAJOR, or MEDIUM issues. Do NOT nitpick.\n" +
                "  CRITICAL: Would cause data loss, security vulnerabilities, system crashes, or broken core functionality.\n" +
                "  MAJOR: Significant bugs, missing key requirements, architectural problems that would require rework.\n" +
                "  MEDIUM: Notable gaps in error handling, performance concerns under real usage, or missing edge cases that users will likely hit.\n" +
                "- Do NOT propose changes for: style preferences, minor naming conventions, theoretical concerns that are unlikely in practice, " +
                "or optimizations that don't matter at current scale.\n" +
                "- If the plan is reasonable and has no critical/major/medium issues, APPROVE it. Most plans should be approved.\n" +
                "- Bias toward action — a shipped improvement beats a perfect plan.\n\n" +
                "DUAL-LEVEL DETAIL RULES:\n" +
                "- The plan you are reviewing contains LOW-LEVEL technical details (file names, classes, methods, etc.). " +
                "Use these details for your analysis — review them thoroughly from your expertise area.\n" +
                "- Your 'analysis' field should reference technical specifics when relevant to your expertise.\n" +
                "- Your 'message' field (shown to the user) MUST be written in plain, non-technical language — " +
                "describe features, behaviors, and outcomes only. NEVER mention file names, classes, APIs, or technical details in the message.\n" +
                "- Questions to users should be about desired behavior and outcomes, not technical choices.\n\n" +
                "PARTICIPATION RULES:\n" +
                "- Provide a concise, focused analysis from your area of expertise.\n" +
                "- Your analysis should be 2-3 sentences covering the most important observations from your domain.\n" +
                "- When approving, briefly state what you evaluated and why it's acceptable.\n" +
                "- Do NOT give generic approvals like 'looks good'. Be specific but brief.\n\n" +
                "Respond in this JSON format:\n" +
                "If the plan looks good from your perspective:\n" +
                "{\"status\": \"APPROVED\", \"analysis\": \"your focused technical analysis — what you evaluated and why it passes\", \"message\": \"concise NON-TECHNICAL summary for the user\"}\n\n" +
                "If you find CRITICAL or MAJOR issues that must be fixed:\n" +
                "{\"status\": \"CHANGES_PROPOSED\", \"analysis\": \"your technical analysis of the issues found\", " +
                "\"proposedChanges\": \"technical description of what should change\", " +
                "\"revisedPlan\": \"updated low-level technical plan\", " +
                "\"revisedPlanDisplaySummary\": \"updated high-level non-technical summary for the user\", " +
                "\"revisedTasks\": [{\"title\": \"low-level technical task name\", \"description\": \"detailed technical description\", " +
                "\"displayTitle\": \"high-level user-facing task name\", \"displayDescription\": \"plain language description\", " +
                "\"estimatedMinutes\": number}, ...], " +
                "\"message\": \"concise NON-TECHNICAL summary for the user\"}\n\n" +
                "If you need the user to answer questions before you can complete your review:\n" +
                "{\"status\": \"NEEDS_CLARIFICATION\", \"analysis\": \"what you've found so far\", " +
                "\"questions\": [\"high-level non-technical question 1\", \"high-level non-technical question 2\"], " +
                "\"message\": \"brief summary of what you need to know\"}\n\n" +
                "IMPORTANT: When proposing changes, you MUST include revisedTasks with the COMPLETE task list (not just changed tasks). " +
                "Each task MUST have both low-level (title/description) and high-level (displayTitle/displayDescription) fields. " +
                "When asking questions, keep them high-level and non-technical. " +
                "Only propose CHANGES_PROPOSED for critical or major issues — approve with notes for medium issues.",
                expertPrompt,
                roundContext,
                suggestionTitle,
                suggestionDescription,
                plan,
                tasksJson != null ? "Current Tasks:\n" + tasksJson + "\n\n" : "",
                previousNotes != null && !previousNotes.isBlank() ?
                        "Previous expert reviews:\n" + previousNotes + "\n\n" : "",
                ownerLockContext
        );
    }

    public CompletableFuture<String> reviewExpertFeedback(String sessionId, String expertDisplayName,
                                                            String expertAnalysis, String proposedChanges,
                                                            String currentPlan, String reviewerRole,
                                                            String reviewerPrompt, String workingDir,
                                                            Consumer<String> progressCallback,
                                                            int reviewRound,
                                                            List<Integer> ownerLockedSections) {
        String roundWarning = "";
        if (reviewRound > 1) {
            roundWarning = "IMPORTANT: This is round " + reviewRound + " of review. The bar for accepting changes is EXTREMELY HIGH. " +
                "Only approve changes that fix CRITICAL regressions introduced by recent plan updates. " +
                "Reject ALL other changes to ensure the review converges. When in doubt, REJECT.\n\n";
        }

        String ownerLockInstruction = "";
        if (ownerLockedSections != null && !ownerLockedSections.isEmpty()) {
            ownerLockInstruction = "OWNER-LOCKED TASKS — PROTECTED BY PROJECT OWNER:\n" +
                "Task indices " + ownerLockedSections + " are owner-locked and essential to the original request.\n" +
                "If the proposed changes would remove any of these tasks or change what they deliver to the user " +
                "(not just how they are implemented), you MUST set apply=false and explain that owner-locked tasks " +
                "cannot have their scope altered. Implementation-level changes to locked tasks (technical approach, " +
                "file choices, method signatures) ARE allowed.\n\n";
        }

        String prompt = String.format(
                "%s\n\n" +
                "%s" +
                "%s" +
                "A %s has reviewed an implementation plan and proposed changes. " +
                "As the %s, evaluate whether these changes are warranted.\n\n" +
                "Current plan:\n%s\n\n" +
                "The %s's analysis:\n%s\n\n" +
                "Their proposed changes:\n%s\n\n" +
                "EVALUATION CRITERIA:\n" +
                "- Do the proposed changes address a CRITICAL or MAJOR issue? Only approve changes that fix real problems.\n" +
                "- Would the changes add unnecessary complexity or scope creep?\n" +
                "- Are the changes compatible with the overall approach and goals?\n" +
                "- From your perspective as %s, do these changes improve the plan for the user?\n\n" +
                "Respond in this JSON format:\n" +
                "{\"valid\": true/false, \"notes\": \"your assessment of why the changes are or aren't valuable\", " +
                "\"apply\": true/false}\n\n" +
                "Set apply=true ONLY if the changes fix a critical or major issue. " +
                "Reject changes that are cosmetic, over-engineered, or add scope without clear value.",
                reviewerPrompt, roundWarning, ownerLockInstruction,
                expertDisplayName, reviewerRole,
                currentPlan, expertDisplayName, expertAnalysis, proposedChanges, reviewerRole
        );

        return sendToClaudeAsync(prompt, sessionId, workingDir, null, progressCallback,
                "review-feedback:" + reviewerRole + "<-" + expertDisplayName, resolveExpertModel(), resolveExpertMaxTurns());
    }

    private CompletableFuture<String> sendToClaudeAsync(String prompt, String sessionId,
                                                         String workingDir, String conversationContext,
                                                         Consumer<String> progressCallback,
                                                         String operationType, String model, int maxTurns) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return sendToClaude(prompt, sessionId, workingDir, conversationContext, progressCallback, operationType, model, maxTurns);
            } catch (ClaudeExecutionException e) {
                // Propagate typed execution failures so callers can inspect ClaudeFailureType
                throw e;
            } catch (Exception e) {
                log.error("[CLAUDE-{}] session={} ERROR: {}", operationType, sessionId, e.getMessage(), e);
                return "{\"status\": \"ERROR\", \"message\": \"" +
                        e.getMessage().replace("\"", "\\\"") + "\"}";
            }
        }, executor);
    }

    private String sendToClaude(String prompt, String sessionId, String workingDir,
                                 String conversationContext,
                                 Consumer<String> progressCallback,
                                 String operationType, String model, int maxTurns) throws Exception {
        // Rate limit gate
        acquireRateLimit();

        // Concurrency gate
        claudeGate.acquire();
        try {
            return sendToClaudeGated(prompt, sessionId, workingDir, conversationContext,
                    progressCallback, operationType, model, maxTurns);
        } finally {
            claudeGate.release();
        }
    }

    private String sendToClaudeGated(String prompt, String sessionId, String workingDir,
                                 String conversationContext,
                                 Consumer<String> progressCallback,
                                 String operationType, String model, int maxTurns) throws Exception {
        long requestId = requestCounter.incrementAndGet();
        String logPrefix = String.format("[CLAUDE-REQ-%d][%s]", requestId, operationType);

        ClaudeExecutionException lastException = null;
        for (int attempt = 1; attempt <= claudeMaxRetries + 1; attempt++) {
            try {
                return executeClaudeProcess(prompt, sessionId, workingDir, conversationContext,
                        progressCallback, operationType, model, maxTurns, logPrefix);
            } catch (ClaudeExecutionException e) {
                lastException = e;
                boolean isPermanent = e.getType() == ClaudeFailureType.PERMANENT;
                boolean isLastAttempt = attempt > claudeMaxRetries;
                if (isPermanent || isLastAttempt) {
                    throw new ClaudeExecutionException(e.getMessage(), e.getType(), attempt);
                }
                long delay = computeRetryDelay(attempt);
                log.warn("{} transient failure on attempt {}/{}, retrying in {}ms: {}",
                        logPrefix, attempt, claudeMaxRetries + 1, delay, e.getMessage());
                Thread.sleep(delay);
            }
        }
        // Should never reach here, but satisfy compiler
        throw lastException;
    }

    long computeRetryDelay(int attempt) {
        long exponential = claudeRetryBaseDelayMs * (1L << (attempt - 1));
        long capped = Math.min(exponential, claudeRetryMaxDelayMs);
        long jitter = (long) (capped * 0.2 * Math.random());
        return capped + jitter;
    }

    private String executeClaudeProcess(String prompt, String sessionId, String workingDir,
                                        String conversationContext,
                                        Consumer<String> progressCallback,
                                        String operationType, String model, int maxTurns,
                                        String logPrefix) throws Exception {
        long startTime = System.currentTimeMillis();

        // Expert reviews and feedback always get a fresh session (no resume)
        boolean isExpertOp = operationType.startsWith("expert-review:") || operationType.startsWith("review-feedback:");
        String cliSessionId = isExpertOp ? null : sessionMap.get(sessionId);
        boolean isResume = cliSessionId != null;

        // Build command with optional model and max-turns flags
        List<String> command = new java.util.ArrayList<>();
        command.add(claudeCliPath);
        if (isResume) {
            command.add("--resume");
            command.add(cliSessionId);
        }
        command.add("-p");
        command.add(prompt);
        command.add("--output-format");
        command.add("json");
        if (model != null && !model.isBlank()) {
            command.add("--model");
            command.add(model);
        }
        if (maxTurns > 0) {
            command.add("--max-turns");
            command.add(String.valueOf(maxTurns));
        }
        if (claudeVerbose) {
            command.add("--verbose");
        }
        command.add("--dangerously-skip-permissions");

        ProcessBuilder pb = new ProcessBuilder(wrapCommandForUser(command));

        if (workingDir != null) {
            pb.directory(new File(workingDir));
        }

        // Propagate SSH key and HOME to Claude CLI subprocess so git operations use SSH auth
        applyGitEnvironment(pb);

        pb.redirectErrorStream(true);
        pb.redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")));

        // Log the request
        log.info("{} session={} resume={} model={} maxTurns={} verbose={} workDir={} promptLength={}",
                logPrefix, sessionId, isResume,
                (model != null && !model.isBlank()) ? model : "default",
                maxTurns > 0 ? maxTurns : "unlimited",
                claudeVerbose,
                workingDir, prompt.length());
        if (claudeVerbose) {
            log.info("{} command: {}", logPrefix, String.join(" ", command));
            log.info("{} prompt: {}", logPrefix, truncate(prompt, MAX_LOG_PROMPT_LENGTH));
        } else {
            log.debug("{} prompt: {}", logPrefix, truncate(prompt, MAX_LOG_PROMPT_LENGTH));
        }

        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            log.error("{} failed to start Claude CLI process: {}", logPrefix, e.getMessage());
            throw new ClaudeExecutionException(
                    "Failed to start Claude CLI process: " + e.getMessage(),
                    ClaudeFailureType.TRANSIENT, 1);
        }

        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
                if (claudeVerbose) {
                    log.info("{} [stdout] {}", logPrefix, line);
                }
                if (progressCallback != null && !isCliJsonEnvelope(line)) {
                    progressCallback.accept(line);
                }
            }
        } catch (IOException e) {
            log.error("{} error reading Claude CLI output: {}", logPrefix, e.getMessage());
            throw new ClaudeExecutionException(
                    "Error reading Claude CLI output: " + e.getMessage(),
                    ClaudeFailureType.TRANSIENT, 1);
        }

        boolean completed = process.waitFor(claudeTimeoutMinutes, TimeUnit.MINUTES);
        if (!completed) {
            process.destroyForcibly();
            long elapsed = System.currentTimeMillis() - startTime;
            log.error("{} TIMEOUT after {}ms (limit={}min) — process killed",
                    logPrefix, elapsed, claudeTimeoutMinutes);
            throw new ClaudeExecutionException(
                    "Claude CLI timed out after " + claudeTimeoutMinutes + " minutes",
                    ClaudeFailureType.TRANSIENT, 1);
        }

        int exitCode = process.exitValue();
        long elapsed = System.currentTimeMillis() - startTime;
        String rawOutput = output.toString().trim();

        // Log raw output size and timing
        log.info("{} completed in {}ms exitCode={} responseLength={}",
                logPrefix, elapsed, exitCode, rawOutput.length());

        // Try to parse as JSON to extract session_id and result
        String resultText = rawOutput;
        String parsedCliSessionId = null;

        try {
            String jsonStr = extractJsonObject(rawOutput);
            if (jsonStr != null) {
                JsonNode root = objectMapper.readTree(jsonStr);

                // Extract the CLI session ID for future --resume calls
                if (root.has("session_id")) {
                    parsedCliSessionId = root.get("session_id").asText();
                }

                // Extract the actual result text
                if (root.has("result")) {
                    resultText = root.get("result").asText();
                }

                // Check for error responses (e.g., dead session)
                if (root.has("is_error") && root.get("is_error").asBoolean()) {
                    String errorMsg = root.has("result") ? root.get("result").asText() : rawOutput;
                    if (isResume && isDeadSessionError(errorMsg)) {
                        log.warn("{} dead session detected, rebuilding context", logPrefix);
                        return handleDeadSession(prompt, sessionId, workingDir,
                                conversationContext, progressCallback, operationType, model, maxTurns);
                    }
                    log.warn("{} error response: {}", logPrefix, truncate(errorMsg, MAX_LOG_RESPONSE_LENGTH));
                    ClaudeFailureType failureType = classifyFailure(rawOutput, exitCode, null);
                    throw new ClaudeExecutionException(
                            "Claude CLI returned is_error: " + truncate(errorMsg, 200),
                            failureType, 1);
                }
            }
        } catch (ClaudeExecutionException e) {
            throw e;
        } catch (Exception e) {
            log.debug("{} could not parse output as JSON: {}", logPrefix, e.getMessage());
        }

        // Fallback: check raw output for dead session error
        if (isResume && isDeadSessionError(rawOutput)) {
            log.warn("{} dead session detected (fallback check), rebuilding context", logPrefix);
            return handleDeadSession(prompt, sessionId, workingDir,
                    conversationContext, progressCallback, operationType, model, maxTurns);
        }

        // Store the CLI session ID for future --resume calls
        if (parsedCliSessionId != null && !parsedCliSessionId.isBlank()) {
            sessionMap.put(sessionId, parsedCliSessionId);
            log.debug("{} stored CLI session: {}", logPrefix, parsedCliSessionId);
        }

        if (exitCode != 0) {
            log.warn("{} non-zero exit code: {}", logPrefix, exitCode);
            ClaudeFailureType failureType = classifyFailure(rawOutput, exitCode, null);
            throw new ClaudeExecutionException(
                    "Claude CLI exited with code " + exitCode,
                    failureType, 1);
        }

        // Log response summary
        if (claudeVerbose) {
            log.info("{} response: {}", logPrefix, truncate(resultText, MAX_LOG_RESPONSE_LENGTH));
        } else {
            log.debug("{} response: {}", logPrefix, truncate(resultText, MAX_LOG_RESPONSE_LENGTH));
        }

        return resultText;
    }

    private static String truncate(String text, int maxLength) {
        if (text == null) return "null";
        if (text.length() <= maxLength) return text;
        return text.substring(0, maxLength) + "...[truncated, total=" + text.length() + "]";
    }

    /**
     * Detect lines that are part of the Claude CLI JSON result envelope
     * (e.g. {"type":"result","subtype":"success",...}) so they are not
     * forwarded as raw progress to the UI.
     */
    private static boolean isCliJsonEnvelope(String line) {
        if (line == null) return false;
        String trimmed = line.trim();
        return trimmed.startsWith("{") && trimmed.contains("\"type\"") &&
                (trimmed.contains("\"result\"") || trimmed.contains("\"system\""));
    }

    boolean isDeadSessionError(String output) {
        return output != null && (
                output.contains("No conversation found") ||
                output.contains("session not found") ||
                output.contains("Session not found"));
    }

    private String handleDeadSession(String prompt, String sessionId, String workingDir,
                                      String conversationContext,
                                      Consumer<String> progressCallback,
                                      String operationType, String model, int maxTurns) throws Exception {
        log.warn("[CLAUDE-{}] session={} is dead, starting fresh session with conversation context",
                operationType, sessionId);
        sessionMap.remove(sessionId);

        String rebuiltPrompt;
        if (conversationContext != null && !conversationContext.isBlank()) {
            rebuiltPrompt = "Here is the prior conversation history for context. " +
                    "Continue naturally from where the conversation left off.\n\n" +
                    "--- CONVERSATION HISTORY ---\n" +
                    conversationContext +
                    "\n--- END HISTORY ---\n\n" +
                    "Now, the user says:\n\n" + prompt;
        } else {
            rebuiltPrompt = prompt;
        }

        // Retry with a fresh session (no resume, no context to avoid infinite loop)
        return sendToClaude(rebuiltPrompt, sessionId, workingDir, null, progressCallback, operationType + "-retry", model, maxTurns);
    }

    /**
     * Extract the outermost JSON object from output that may contain
     * non-JSON lines (warnings, verbose output, etc.) before or after the JSON.
     */
    String extractJsonObject(String raw) {
        int start = raw.indexOf('{');
        if (start < 0) return null;

        int depth = 0;
        boolean inString = false;
        boolean escaped = false;

        for (int i = start; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (c == '\\' && inString) {
                escaped = true;
                continue;
            }
            if (c == '"') {
                inString = !inString;
                continue;
            }
            if (!inString) {
                if (c == '{') depth++;
                else if (c == '}') {
                    depth--;
                    if (depth == 0) {
                        return raw.substring(start, i + 1);
                    }
                }
            }
        }
        return null;
    }

    /**
     * Clone the repository into main-repo/ for the initial Claude session workspace.
     */
    public String cloneMainRepository(String repoUrl) throws Exception {
        String targetDir = workspaceDir + "/main-repo";
        return gitClone(repoUrl, targetDir);
    }

    /**
     * Pull the latest changes in main-repo/. If the repo doesn't exist yet, clone it.
     * Returns true if the pull brought in new changes (HEAD moved).
     */
    public boolean pullMainRepository(String repoUrl) throws Exception {
        String targetDir = workspaceDir + "/main-repo";
        File dir = new File(targetDir);
        File gitDir = new File(targetDir, ".git");

        if (!dir.exists() || !gitDir.exists()) {
            cloneMainRepository(repoUrl);
            return true;
        }

        // Get HEAD before pull
        String headBefore = getHeadCommit(targetDir);

        ProcessBuilder pb = new ProcessBuilder(wrapCommandForUser(
                java.util.List.of("git", "pull")));
        pb.directory(dir);
        pb.redirectErrorStream(true);
        pb.redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")));

        applyGitEnvironment(pb);

        Process process = pb.start();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                log.info("Git pull main-repo: {}", line);
            }
        }

        int exitCode = process.waitFor();
        if (exitCode != 0) {
            log.warn("Git pull failed (exit {}), falling back to fresh clone", exitCode);
            cloneMainRepository(repoUrl);
            return true;
        }

        // Get HEAD after pull
        String headAfter = getHeadCommit(targetDir);
        boolean changed = !headAfter.equals(headBefore);
        if (changed) {
            log.info("main-repo updated: {} -> {}", headBefore, headAfter);
        } else {
            log.info("main-repo already up to date at {}", headAfter);
        }
        return changed;
    }

    /**
     * Fetch the latest from origin in the given repo directory.
     */
    public void fetchOrigin(String repoDir) throws Exception {
        File dir = new File(repoDir);
        if (!dir.exists() || !new File(repoDir, ".git").exists()) {
            log.warn("Repo does not exist for fetch: {}", repoDir);
            return;
        }

        ProcessBuilder fetchPb = new ProcessBuilder(wrapCommandForUser(
                java.util.List.of("git", "fetch", "origin")));
        fetchPb.directory(dir);
        fetchPb.redirectErrorStream(true);
        fetchPb.redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")));
        applyGitEnvironment(fetchPb);
        Process fetchProcess = fetchPb.start();
        consumeStream(fetchProcess.getInputStream());
        fetchProcess.waitFor();
        log.info("Fetched origin in {}", repoDir);
    }

    /**
     * Detect the default branch name (main or master) for the given repo.
     */
    public String detectDefaultBranchPublic(String repoDir) throws Exception {
        return detectDefaultBranch(repoDir);
    }

    /**
     * Ask Claude to merge origin/&lt;default-branch&gt; into the suggestion repo's current branch,
     * resolving any merge conflicts intelligently rather than aborting.
     * Also asks Claude to assess whether the merged changes affect the suggestion's plan.
     *
     * Returns a CompletableFuture with Claude's JSON response describing the merge result.
     */
    public CompletableFuture<String> mergeWithMain(String sessionId, String workingDir,
                                                     String mergePrompt,
                                                     String conversationContext,
                                                     Consumer<String> progressCallback) {
        return sendToClaudeAsync(mergePrompt, sessionId, workingDir, conversationContext,
                progressCallback, "merge-main", resolveModel(), 0);
    }

    /**
     * Find all suggestion repo directories that currently exist on disk.
     */
    public List<String> findSuggestionRepoDirs() {
        File workspace = new File(workspaceDir);
        List<String> dirs = new java.util.ArrayList<>();
        if (!workspace.exists()) return dirs;

        File[] files = workspace.listFiles();
        if (files == null) return dirs;

        for (File f : files) {
            if (f.isDirectory() && f.getName().startsWith("suggestion-") && f.getName().endsWith("-repo")) {
                dirs.add(f.getAbsolutePath());
            }
        }
        return dirs;
    }

    private String getHeadCommit(String repoDir) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(wrapCommandForUser(
                java.util.List.of("git", "rev-parse", "HEAD")));
        pb.directory(new File(repoDir));
        pb.redirectErrorStream(true);
        Process process = pb.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        process.waitFor();
        return output;
    }

    private String detectDefaultBranch(String repoDir) throws Exception {
        // Check if origin/main exists
        ProcessBuilder pb = new ProcessBuilder(wrapCommandForUser(
                java.util.List.of("git", "rev-parse", "--verify", "origin/main")));
        pb.directory(new File(repoDir));
        pb.redirectErrorStream(true);
        Process process = pb.start();
        consumeStream(process.getInputStream());
        int exitCode = process.waitFor();
        return exitCode == 0 ? "main" : "master";
    }

    private void consumeStream(InputStream is) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            while (reader.readLine() != null) { /* drain */ }
        }
    }

    /**
     * Clone the repository into suggestion-{id}-repo/ when a suggestion is approved.
     */
    public String cloneRepository(String repoUrl, String suggestionId) throws Exception {
        String targetDir = workspaceDir + "/suggestion-" + suggestionId + "-repo";
        return gitClone(repoUrl, targetDir);
    }

    private String gitClone(String repoUrl, String targetDir) throws Exception {
        File dir = new File(targetDir);
        if (dir.exists()) {
            ProcessBuilder cleanup = new ProcessBuilder("rm", "-rf", targetDir);
            cleanup.start().waitFor();
        }

        String sshRepoUrl = toSshUrl(repoUrl);
        ProcessBuilder pb = new ProcessBuilder(wrapCommandForUser(
                java.util.List.of("git", "clone", sshRepoUrl, targetDir)));
        pb.redirectErrorStream(true);
        pb.redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")));

        applyGitEnvironment(pb);

        Process process = pb.start();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                log.info("Git clone: {}", line);
            }
        }

        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new RuntimeException("Failed to clone repository: exit code " + exitCode);
        }

        log.info("Repository cloned to {}", targetDir);
        markDirectoryTrusted(targetDir);
        return targetDir;
    }

    /**
     * Mark {@code absolutePath} as trusted in {@code ~/.claude.json} so the CLI
     * skips the interactive trust dialog when invoked with this directory as
     * cwd. Idempotent — does nothing if the entry already exists.
     */
    public void markDirectoryTrusted(String absolutePath) {
        if (absolutePath == null || absolutePath.isBlank()) {
            return;
        }
        try {
            Path configFile = Path.of(getClaudeCliHome(), ".claude.json");
            com.fasterxml.jackson.databind.node.ObjectNode root;
            if (Files.exists(configFile)) {
                JsonNode parsed = objectMapper.readTree(configFile.toFile());
                root = parsed.isObject()
                        ? (com.fasterxml.jackson.databind.node.ObjectNode) parsed
                        : objectMapper.createObjectNode();
            } else {
                root = objectMapper.createObjectNode();
                Files.createDirectories(configFile.getParent());
            }
            com.fasterxml.jackson.databind.node.ObjectNode projects =
                    root.has("projects") && root.get("projects").isObject()
                            ? (com.fasterxml.jackson.databind.node.ObjectNode) root.get("projects")
                            : root.putObject("projects");
            com.fasterxml.jackson.databind.node.ObjectNode entry =
                    projects.has(absolutePath) && projects.get(absolutePath).isObject()
                            ? (com.fasterxml.jackson.databind.node.ObjectNode) projects.get(absolutePath)
                            : projects.putObject(absolutePath);
            boolean alreadyTrusted = entry.path("hasTrustDialogAccepted").asBoolean(false)
                    && entry.path("hasCompletedProjectOnboarding").asBoolean(false);
            if (alreadyTrusted) {
                return;
            }
            entry.put("hasTrustDialogAccepted", true);
            entry.put("hasCompletedProjectOnboarding", true);
            Files.writeString(configFile, objectMapper.writeValueAsString(root), StandardCharsets.UTF_8);
            chownToRunAsUser(configFile);
            log.info("Marked {} as trusted in Claude CLI config", absolutePath);
        } catch (Exception e) {
            log.warn("Failed to mark {} as trusted in Claude config: {}", absolutePath, e.getMessage());
        }
    }

    /**
     * Materialize the SSH key stored in site_settings to a 0600 file on disk so it can
     * be passed to ssh via GIT_SSH_COMMAND. Returns the file path, or null if no key
     * is configured in settings or materialization fails. Idempotent: only rewrites
     * when the key content has changed.
     */
    String materializeSettingsSshKey() {
        String keyContent;
        try {
            keyContent = getSettings().getGitSshKey();
        } catch (Exception e) {
            log.debug("Could not read git SSH key from settings: {}", e.getMessage());
            return null;
        }
        if (keyContent == null || keyContent.isBlank()) {
            return null;
        }

        // SSH refuses keys without a trailing newline
        String normalized = keyContent.endsWith("\n") ? keyContent : keyContent + "\n";

        try {
            File sshDir = new File(workspaceDir, ".ssh");
            if (!sshDir.exists() && !sshDir.mkdirs()) {
                log.warn("Failed to create directory for settings SSH key: {}", sshDir);
                return null;
            }
            Path keyFile = new File(sshDir, "settings_git_id").toPath();

            boolean needsWrite = !Files.exists(keyFile) ||
                    !new String(Files.readAllBytes(keyFile), StandardCharsets.UTF_8).equals(normalized);
            if (needsWrite) {
                Files.write(keyFile, normalized.getBytes(StandardCharsets.UTF_8));
                Set<PosixFilePermission> perms = PosixFilePermissions.fromString("rw-------");
                try {
                    Files.setPosixFilePermissions(keyFile, perms);
                } catch (UnsupportedOperationException ignored) {
                    // Non-POSIX filesystem; best-effort fall-through (key may still work)
                }
                // When subprocesses run as a different OS user, the key file must be
                // readable by that user — chown it.
                if (shouldRunAsDifferentUser()) {
                    try {
                        ProcessBuilder chownPb = new ProcessBuilder(
                                "chown", claudeRunAsUser + ":" + claudeRunAsUser, keyFile.toString());
                        chownPb.redirectErrorStream(true);
                        Process chownProcess = chownPb.start();
                        chownProcess.getInputStream().readAllBytes();
                        chownProcess.waitFor();
                    } catch (Exception e) {
                        log.warn("Failed to chown settings SSH key to {}: {}", claudeRunAsUser, e.getMessage());
                    }
                }
                log.info("Materialized settings SSH key at {}", keyFile);
            }
            return keyFile.toString();
        } catch (Exception e) {
            log.error("Failed to materialize settings SSH key: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * Resolve the home directory of the run-as-user (configured via
     * {@code app.claude-run-as-user}). Returns null if not running as a different
     * user. Queries /etc/passwd via {@code getent} and falls back to /home/&lt;user&gt;.
     */
    /**
     * Public accessor: returns the home directory used by Claude CLI subprocesses.
     * Falls back to the JVM's HOME (current user) when no run-as-user is configured.
     */
    public String getClaudeCliHome() {
        String runAsHome = resolveRunAsUserHome();
        if (runAsHome != null) {
            return runAsHome;
        }
        String home = System.getenv("HOME");
        return (home != null && !home.isBlank()) ? home : System.getProperty("user.home");
    }

    /**
     * Returns the configured run-as-user (may be null/blank if the CLI runs as the
     * current process user).
     */
    public String getClaudeRunAsUser() {
        return claudeRunAsUser;
    }

    private String resolveRunAsUserHome() {
        if (!shouldRunAsDifferentUser()) {
            return null;
        }
        try {
            ProcessBuilder pb = new ProcessBuilder("getent", "passwd", claudeRunAsUser);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            p.waitFor();
            // passwd format: username:x:uid:gid:gecos:home:shell
            String[] parts = output.split(":");
            if (parts.length >= 6 && !parts[5].isBlank()) {
                return parts[5];
            }
        } catch (Exception e) {
            log.debug("Could not resolve home for user {}: {}", claudeRunAsUser, e.getMessage());
        }
        return "/home/" + claudeRunAsUser;
    }

    /**
     * Configure a ProcessBuilder's environment for git operations: sets
     * GIT_SSH_COMMAND if an SSH key is available, and sets HOME to the
     * run-as-user's home so ssh can read its known_hosts/config and write
     * back to known_hosts when adding a new host.
     */
    private void applyGitEnvironment(ProcessBuilder pb) {
        String runAsHome = resolveRunAsUserHome();
        if (runAsHome != null) {
            pb.environment().put("HOME", runAsHome);
        }
        String sshKeyPath = resolveGitSshKeyPath();
        if (sshKeyPath != null) {
            pb.environment().put("GIT_SSH_COMMAND",
                    "ssh -i " + sshKeyPath + " -o StrictHostKeyChecking=no -o IdentitiesOnly=yes");
        }
    }

    /**
     * Resolve the SSH key path. Prefers a key configured via the Settings page
     * (stored in the DB, materialized to a 0600 file), then the explicitly
     * configured app.git-ssh-key-path, then the run-as-user's ~/.ssh, then
     * auto-detects from the current user's ~/.ssh (id_rsa, id_ed25519, etc.),
     * or falls back to the git config core.sshCommand key if present.
     */
    private String resolveGitSshKeyPath() {
        // 1. Key configured via Settings page takes priority
        String settingsKeyPath = materializeSettingsSshKey();
        if (settingsKeyPath != null) {
            return settingsKeyPath;
        }

        // 2. Use explicitly configured path next
        if (gitSshKeyPath != null && !gitSshKeyPath.isBlank()) {
            File key = new File(gitSshKeyPath);
            if (key.exists() && key.isFile()) {
                return gitSshKeyPath;
            }
            log.warn("Configured SSH key not found at {}", gitSshKeyPath);
        }

        String[] candidates = {"id_rsa", "id_ed25519", "id_ecdsa", "id_dsa"};

        // If running git as a different user, prefer that user's ~/.ssh — keys
        // in root's ~/.ssh aren't readable by an unprivileged user via runuser.
        String runAsHome = resolveRunAsUserHome();
        if (runAsHome != null) {
            String runAsSshDir = runAsHome + "/.ssh";
            for (String candidate : candidates) {
                File key = new File(runAsSshDir, candidate);
                if (key.exists() && key.isFile()) {
                    log.info("Auto-detected SSH key (run-as-user): {}", key.getAbsolutePath());
                    return key.getAbsolutePath();
                }
            }
        }

        // Auto-detect from ~/.ssh
        String userHome = System.getProperty("user.home");
        if (userHome == null) {
            return null;
        }

        String sshDir = userHome + "/.ssh";
        for (String candidate : candidates) {
            File key = new File(sshDir, candidate);
            if (key.exists() && key.isFile()) {
                log.info("Auto-detected SSH key: {}", key.getAbsolutePath());
                return key.getAbsolutePath();
            }
        }

        // Check if git config has a key configured
        try {
            ProcessBuilder pb = new ProcessBuilder(wrapCommandForUser(
                    java.util.List.of("git", "config", "--get", "core.sshCommand")));
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            p.waitFor();
            if (!output.isBlank()) {
                log.info("Using git config core.sshCommand: {}", output);
                // Already handled by git itself, no need to override
                return null;
            }
        } catch (Exception e) {
            log.debug("Could not read git config core.sshCommand: {}", e.getMessage());
        }

        log.info("No SSH key found; git clone will use default SSH agent or credentials");
        return null;
    }

    /**
     * Convert an HTTPS GitHub URL to SSH format for SSH-based authentication.
     * If the URL is already SSH or not a recognized HTTPS GitHub URL, return as-is.
     */
    private String toSshUrl(String repoUrl) {
        if (repoUrl == null) {
            return repoUrl;
        }
        // Already SSH format
        if (repoUrl.startsWith("git@")) {
            return repoUrl;
        }
        // Convert https://github.com/owner/repo(.git) to git@github.com:owner/repo.git
        if (repoUrl.startsWith("https://github.com/") || repoUrl.startsWith("http://github.com/")) {
            String path = repoUrl.replaceFirst("https?://github\\.com/", "");
            if (!path.endsWith(".git")) {
                path = path + ".git";
            }
            return "git@github.com:" + path;
        }
        // Non-GitHub URL, return as-is
        return repoUrl;
    }

    /**
     * Push a branch to the remote origin.
     */
    public void pushBranch(String repoDir, String branchName) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(wrapCommandForUser(
                java.util.List.of("git", "push", "-u", "origin", branchName)));
        pb.directory(new File(repoDir));
        pb.redirectErrorStream(true);
        pb.redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")));

        applyGitEnvironment(pb);

        Process process = pb.start();
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
                log.info("Git push: {}", line);
            }
        }

        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new RuntimeException("Failed to push branch " + branchName + ": " + output.toString().trim());
        }
        log.info("Pushed branch {} in {}", branchName, repoDir);
    }

    /**
     * Stage all changes and commit them in the given repo directory.
     * Returns true if a commit was created, false if there were no changes to commit.
     */
    public boolean commitAllChanges(String repoDir, String message) throws Exception {
        stageAllChanges(repoDir);
        return commitStagedChanges(repoDir, message);
    }

    public void stageAllChanges(String repoDir) throws Exception {
        File dir = new File(repoDir);
        ProcessBuilder addPb = new ProcessBuilder(wrapCommandForUser(
                java.util.List.of("git", "add", "-A")));
        addPb.directory(dir);
        addPb.redirectErrorStream(true);
        addPb.redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")));
        Process addProcess = addPb.start();
        String addOutput = new String(addProcess.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        int addExit = addProcess.waitFor();
        if (addExit != 0) {
            throw new RuntimeException("Failed to stage changes: " + addOutput);
        }
    }

    public boolean commitStagedChanges(String repoDir, String message) throws Exception {
        File dir = new File(repoDir);

        // Check if there are staged changes (exit 0 = no changes, exit 1 = changes exist)
        ProcessBuilder diffPb = new ProcessBuilder(wrapCommandForUser(
                java.util.List.of("git", "diff", "--cached", "--quiet")));
        diffPb.directory(dir);
        diffPb.redirectErrorStream(true);
        diffPb.redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")));
        Process diffProcess = diffPb.start();
        diffProcess.getInputStream().readAllBytes();
        int diffExit = diffProcess.waitFor();
        if (diffExit == 0) {
            log.warn("No changes to commit in {}", repoDir);
            return false;
        }

        // Commit with embedded author identity (avoids needing global git config)
        ProcessBuilder commitPb = new ProcessBuilder(wrapCommandForUser(
                java.util.List.of("git", "-c", "user.name=Site Manager", "-c", "user.email=site-manager@noreply",
                "commit", "-m", message)));
        commitPb.directory(dir);
        commitPb.redirectErrorStream(true);
        commitPb.redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")));
        Process commitProcess = commitPb.start();
        StringBuilder commitOutput = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(commitProcess.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                commitOutput.append(line).append("\n");
                log.info("Git commit: {}", line);
            }
        }
        int commitExit = commitProcess.waitFor();
        if (commitExit != 0) {
            throw new RuntimeException("Failed to commit changes: " + commitOutput.toString().trim());
        }
        log.info("Committed changes in {} with message: {}", repoDir, message);
        return true;
    }

    public String getStagedDiffSummary(String repoDir) throws Exception {
        File dir = new File(repoDir);

        // Get the stat summary
        ProcessBuilder statPb = new ProcessBuilder(wrapCommandForUser(
                java.util.List.of("git", "diff", "--cached", "--stat")));
        statPb.directory(dir);
        statPb.redirectErrorStream(true);
        statPb.redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")));
        Process statProcess = statPb.start();
        String stat = new String(statProcess.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        statProcess.waitFor();

        // Get the actual diff (truncated)
        ProcessBuilder diffPb = new ProcessBuilder(wrapCommandForUser(
                java.util.List.of("git", "diff", "--cached")));
        diffPb.directory(dir);
        diffPb.redirectErrorStream(true);
        diffPb.redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")));
        Process diffProcess = diffPb.start();
        String diff = new String(diffProcess.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        diffProcess.waitFor();

        int maxDiffLen = 4000;
        if (diff.length() > maxDiffLen) {
            diff = diff.substring(0, maxDiffLen) + "\n... (diff truncated)";
        }

        return stat + "\n\n" + diff;
    }

    public String generateCommitMessage(String sessionId, String suggestionTitle,
                                         String suggestionDescription, String diffSummary) {
        String prompt = "Generate a concise git commit message for the following changes.\n\n" +
                "Suggestion title: " + suggestionTitle + "\n" +
                "Suggestion description: " + suggestionDescription + "\n\n" +
                "Diff summary:\n" + diffSummary + "\n\n" +
                "Rules:\n" +
                "- Write ONLY the commit message, nothing else\n" +
                "- First line: short summary (max 72 chars), imperative mood\n" +
                "- Optionally follow with a blank line and a brief body\n" +
                "- Do NOT wrap in quotes or backticks\n" +
                "- Do NOT include any explanation or preamble";

        try {
            String response = sendToClaude(prompt, sessionId, null, null, null,
                    "generate-commit-message", resolveModel(), 0);
            String message = response.strip();
            if (message.isEmpty()) {
                throw new RuntimeException("Empty commit message from Claude");
            }
            return message;
        } catch (Exception e) {
            log.error("Failed to generate commit message via Claude: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to generate commit message", e);
        }
    }

    /**
     * Get the commit log for a branch relative to the default branch.
     */
    public String getCommitLog(String repoDir) throws Exception {
        String defaultBranch = detectDefaultBranch(repoDir);
        ProcessBuilder pb = new ProcessBuilder(wrapCommandForUser(
                java.util.List.of("git", "log",
                "origin/" + defaultBranch + "..HEAD", "--pretty=format:%s")));
        pb.directory(new File(repoDir));
        pb.redirectErrorStream(true);
        Process process = pb.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        process.waitFor();
        return output;
    }

    /**
     * Create a pull request on GitHub using the REST API.
     * Returns a map with "html_url" and "number" from the response.
     */
    public Map<String, Object> createGitHubPullRequest(String repoUrl, String branchName,
                                                        String title, String body,
                                                        String githubToken) throws Exception {
        // Extract owner/repo from the URL
        String ownerRepo = extractOwnerRepo(repoUrl);
        if (ownerRepo == null) {
            throw new RuntimeException("Cannot extract owner/repo from URL: " + repoUrl);
        }

        // Fetch the repository's actual default branch from the GitHub API
        HttpClient client = HttpClient.newHttpClient();
        String defaultBranch = fetchDefaultBranch(client, ownerRepo, githubToken);
        log.info("Using default branch '{}' as PR base for {}", defaultBranch, ownerRepo);

        String jsonBody = objectMapper.writeValueAsString(Map.of(
                "title", title,
                "body", body,
                "head", branchName,
                "base", defaultBranch
        ));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.github.com/repos/" + ownerRepo + "/pulls"))
                .header("Authorization", "Bearer " + githubToken)
                .header("Accept", "application/vnd.github+json")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 201) {
            throw new RuntimeException("GitHub API error (" + response.statusCode() + "): " + response.body());
        }

        JsonNode root = objectMapper.readTree(response.body());
        String htmlUrl = root.get("html_url").asText();
        int number = root.get("number").asInt();

        return Map.of("html_url", htmlUrl, "number", number);
    }

    /**
     * Merge a pull request on GitHub using the REST API.
     * Returns true if the merge succeeded (HTTP 200), false otherwise.
     * IMPORTANT: never log the githubToken parameter at any log level.
     */
    public boolean mergePullRequest(String repoUrl, int prNumber, String githubToken) {
        return mergePullRequest(repoUrl, prNumber, githubToken, HttpClient.newHttpClient());
    }

    /**
     * Package-private overload for testability — callers should use the public variant.
     */
    boolean mergePullRequest(String repoUrl, int prNumber, String githubToken, HttpClient client) {
        if (githubToken == null || githubToken.isEmpty()) {
            log.warn("mergePullRequest called with null or empty token, skipping merge");
            return false;
        }

        String ownerRepo = extractOwnerRepo(repoUrl);
        if (ownerRepo == null) {
            log.warn("mergePullRequest: cannot extract owner/repo from URL: {}", repoUrl);
            return false;
        }

        try {
            String jsonBody = objectMapper.writeValueAsString(Map.of("merge_method", "merge"));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.github.com/repos/" + ownerRepo + "/pulls/" + prNumber + "/merge"))
                    .header("Authorization", "Bearer " + githubToken) // token is never logged
                    .header("Accept", "application/vnd.github+json")
                    .header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                log.info("Successfully merged PR #{} in {}", prNumber, ownerRepo);
                return true;
            }

            log.warn("mergePullRequest: GitHub returned status={} body={} for PR #{} in {}",
                    response.statusCode(), response.body(), prNumber, ownerRepo);
            return false;
        } catch (Exception e) {
            log.warn("mergePullRequest: unexpected error for PR #{} in {}: {}", prNumber, ownerRepo, e.getMessage());
            return false;
        }
    }

    /**
     * Fetch the default branch name for a GitHub repository via the REST API.
     * Falls back to "main" if the API call fails.
     */
    private String fetchDefaultBranch(HttpClient client, String ownerRepo, String githubToken) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.github.com/repos/" + ownerRepo))
                    .header("Authorization", "Bearer " + githubToken)
                    .header("Accept", "application/vnd.github+json")
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                JsonNode repo = objectMapper.readTree(response.body());
                JsonNode branch = repo.get("default_branch");
                if (branch != null && !branch.isNull()) {
                    return branch.asText();
                }
            }
            log.warn("Could not fetch default branch for {} (status={}), falling back to 'main'",
                    ownerRepo, response.statusCode());
        } catch (Exception e) {
            log.warn("Failed to fetch default branch for {}: {}, falling back to 'main'",
                    ownerRepo, e.getMessage());
        }
        return "main";
    }

    /**
     * Extract "owner/repo" from a GitHub URL.
     */
    private String extractOwnerRepo(String repoUrl) {
        if (repoUrl == null) return null;

        // Handle SSH format: git@github.com:owner/repo.git
        if (repoUrl.startsWith("git@github.com:")) {
            String path = repoUrl.substring("git@github.com:".length());
            if (path.endsWith(".git")) path = path.substring(0, path.length() - 4);
            return path;
        }

        // Handle HTTPS format: https://github.com/owner/repo(.git)
        if (repoUrl.contains("github.com/")) {
            String path = repoUrl.replaceFirst(".*github\\.com/", "");
            if (path.endsWith(".git")) path = path.substring(0, path.length() - 4);
            // Remove trailing slash
            if (path.endsWith("/")) path = path.substring(0, path.length() - 1);
            return path;
        }

        return null;
    }

    public void shutdown() {
        executor.shutdown();
    }

    // -------------------------------------------------------------------------
    // Failure classification
    // -------------------------------------------------------------------------

    public enum ClaudeFailureType {
        TRANSIENT, PERMANENT
    }

    public static class ClaudeExecutionException extends RuntimeException {
        private final ClaudeFailureType type;
        private final int attemptNumber;

        public ClaudeExecutionException(String message, ClaudeFailureType type, int attemptNumber) {
            super(message);
            this.type = type;
            this.attemptNumber = attemptNumber;
        }

        public ClaudeFailureType getType() { return type; }
        public int getAttemptNumber() { return attemptNumber; }
    }

    /**
     * Classify a Claude CLI failure as TRANSIENT (worth retrying) or PERMANENT (do not retry).
     *
     * <p>PERMANENT conditions:</p>
     * <ul>
     *   <li>exit code 2 (CLI usage / argument error)</li>
     *   <li>{@code is_error} JSON flag combined with authentication or model-not-found messages</li>
     *   <li>successful exit (code 0) with null or empty output</li>
     * </ul>
     *
     * <p>TRANSIENT conditions (checked after PERMANENT):</p>
     * <ul>
     *   <li>{@link IOException} as the root cause</li>
     *   <li>cause message or raw output containing "timed out"</li>
     *   <li>raw output containing "rate limit" or "overloaded"</li>
     *   <li>any other non-zero exit code</li>
     * </ul>
     */
    ClaudeFailureType classifyFailure(String rawOutput, int exitCode, Throwable cause) {
        // PERMANENT: exit code 2 indicates a CLI argument / usage error
        if (exitCode == 2) {
            return ClaudeFailureType.PERMANENT;
        }

        // PERMANENT: is_error with authentication or model-not-found messages
        if (rawOutput != null && rawOutput.contains("is_error")) {
            String lower = rawOutput.toLowerCase();
            if (lower.contains("authentication") || lower.contains("unauthorized") ||
                    lower.contains("invalid api key") || lower.contains("model not found") ||
                    lower.contains("model_not_found") || lower.contains("invalid_api_key")) {
                return ClaudeFailureType.PERMANENT;
            }
        }

        // PERMANENT: successful exit but null/empty output (broken CLI or config issue)
        if (exitCode == 0 && (rawOutput == null || rawOutput.isBlank())) {
            return ClaudeFailureType.PERMANENT;
        }

        // TRANSIENT: IOException during process start or stream read
        if (cause instanceof IOException) {
            return ClaudeFailureType.TRANSIENT;
        }

        // TRANSIENT: timed out (from cause message or raw output)
        if (cause != null && cause.getMessage() != null && cause.getMessage().contains("timed out")) {
            return ClaudeFailureType.TRANSIENT;
        }

        // TRANSIENT: rate-limit or overload signals in output
        if (rawOutput != null) {
            String lower = rawOutput.toLowerCase();
            if (lower.contains("rate limit") || lower.contains("overloaded")) {
                return ClaudeFailureType.TRANSIENT;
            }
        }

        // TRANSIENT: any remaining non-zero exit code (not auth, not exit-2)
        if (exitCode != 0) {
            return ClaudeFailureType.TRANSIENT;
        }

        // Default: TRANSIENT for unexpected conditions
        return ClaudeFailureType.TRANSIENT;
    }
}
