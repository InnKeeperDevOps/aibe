package com.sitemanager.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sitemanager.model.ClaudeCliLog;
import com.sitemanager.model.SiteSettings;
import com.sitemanager.model.enums.ExpertRole;
import com.sitemanager.repository.ClaudeCliLogRepository;
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

    /**
     * Most recent cost record per in-memory session ID. Populated whenever a
     * CLI call returns a parseable result envelope; consumed by callers that
     * need to persist what the call cost (e.g. expert reviews). Entries are
     * removed by {@link #pollSessionCost(String)} so each cost is consumed
     * exactly once and the map does not grow unbounded.
     */
    private final ConcurrentMap<String, ClaudeCostInfo> sessionCosts = new ConcurrentHashMap<>();

    /**
     * Every CLI call that has entered {@link #sendToClaude} but not yet finished —
     * keyed by requestId. Powers the admin Claude Queue page: callers can see what
     * is waiting on rate limit / concurrency / actively running.
     */
    private final ConcurrentMap<Long, QueueEntry> requestRegistry = new ConcurrentHashMap<>();

    /** One in-flight CLI request, observable from the admin Claude Queue page. */
    public static final class QueueEntry {
        public static final String PHASE_AWAITING_RATE_LIMIT = "AWAITING_RATE_LIMIT";
        public static final String PHASE_AWAITING_CONCURRENCY = "AWAITING_CONCURRENCY";
        public static final String PHASE_RUNNING = "RUNNING";

        final long requestId;
        final String operationType;
        final String model;
        final String sessionId;
        final String workingDir;
        final long enqueuedAt;
        volatile String phase;
        volatile long phaseChangedAt;

        QueueEntry(long requestId, String operationType, String model,
                   String sessionId, String workingDir, String initialPhase) {
            this.requestId = requestId;
            this.operationType = operationType;
            this.model = (model != null && !model.isBlank()) ? model : "default";
            this.sessionId = sessionId;
            this.workingDir = workingDir;
            this.enqueuedAt = System.currentTimeMillis();
            this.phaseChangedAt = this.enqueuedAt;
            this.phase = initialPhase;
        }

        void setPhase(String newPhase) {
            this.phase = newPhase;
            this.phaseChangedAt = System.currentTimeMillis();
        }
    }

    // FIFO rate limiter: fair ReentrantLock guarantees threads acquire in arrival order
    private long[] callTimestamps;
    private int timestampHead = 0;
    private final java.util.concurrent.locks.ReentrantLock rateLimitLock =
            new java.util.concurrent.locks.ReentrantLock(true); // fair = FIFO

    // Concurrency gate: fair semaphore limits parallel CLI processes in FIFO order
    private final Semaphore claudeGate;

    private final SiteSettingsRepository settingsRepository;
    private final ClaudeCliLogRepository cliLogRepository;

    /** Per-text-field cap for persisted CLI logs, guarding against pathological DB bloat. */
    private static final int MAX_CLI_LOG_FIELD = 200_000;

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

    @Value("${app.claude-max-turns-expert:0}")
    private int claudeMaxTurnsExpertDefault;

    @Value("${app.claude-max-concurrent:1}")
    private int claudeMaxConcurrent;

    @Value("${app.claude-max-calls-per-minute:10}")
    private int claudeMaxCallsPerMinute;

    @Value("${app.claude-max-retries:3}")
    private int claudeMaxRetries;

    @Value("${app.claude-retry-base-delay-ms:2000}")
    private long claudeRetryBaseDelayMs;

    @Value("${app.claude-retry-max-delay-ms:30000}")
    private long claudeRetryMaxDelayMs;

    public ClaudeService(SiteSettingsRepository settingsRepository,
                         ClaudeCliLogRepository cliLogRepository) {
        this.settingsRepository = settingsRepository;
        this.cliLogRepository = cliLogRepository;
        // Defaults; @PostConstruct re-inits with configured values
        this.claudeGate = new Semaphore(1, true); // fair = FIFO ordering
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
        // Claude CLI refuses --dangerously-skip-permissions when invoked as root.
        // If the process is running as root and no run-as user is configured,
        // default to "claude" so subprocesses are wrapped via runuser.
        if (isProcessRunningAsRoot()
                && (claudeRunAsUser == null || claudeRunAsUser.isBlank())) {
            claudeRunAsUser = "claude";
            log.info("Process running as root with no app.claude-run-as-user configured; "
                    + "defaulting to '{}' to satisfy Claude CLI --dangerously-skip-permissions restriction",
                    claudeRunAsUser);
        }
        if (shouldRunAsDifferentUser()) {
            ensureUserExists(claudeRunAsUser);
            log.info("Claude CLI subprocesses will run as user '{}' (current user is root)", claudeRunAsUser);
        }
        resolveClaudeCliPath();
        prepareClaudeCliHomeDir();
        materializeStoredClaudeCredentials();
        materializeStoredClaudeConfig();
        sweepOrphanedTrashOnStartup();
    }

    /**
     * Ensure {@code <claude-home>/.claude/} exists AND is owned by the run-as
     * user. The Java process runs as root and {@code Files.createDirectories}
     * leaves the new dir owned by root with default permissions, so the
     * runuser-wrapped CLI subprocess can't create sub-directories beneath it
     * (e.g. {@code session-env/}) — every CLI call would then die with
     * {@code EACCES: permission denied, mkdir '/home/claude/.claude/session-env'}
     * and take its bash tool down with it.
     */
    private void prepareClaudeCliHomeDir() {
        try {
            Path claudeDir = Path.of(getClaudeCliHome(), ".claude");
            Files.createDirectories(claudeDir);
            chownToRunAsUser(claudeDir);
        } catch (Exception e) {
            log.warn("Could not prepare {}/.claude ownership: {}", getClaudeCliHome(), e.getMessage());
        }
    }

    /**
     * Last-seen modification time of {@code ~/.claude/.credentials.json}, used to
     * detect when the CLI has refreshed the OAuth tokens so the new copy can be
     * persisted back to site_settings. Null until the first materialize/persist.
     */
    private volatile java.nio.file.attribute.FileTime lastCredentialsMtime;

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
            // Ensure ~/.claude/ itself is owned by the run-as user — Files.createDirectories
            // creates it owned by root (the JVM user), and the CLI subprocess later needs
            // to mkdir session-env/ and friends inside it.
            chownToRunAsUser(credsFile.getParent());
            // Don't clobber a disk credentials file that is newer than the stored
            // copy: the CLI refreshes/rotates OAuth tokens on disk, and overwriting
            // them with a stale DB copy (whose refresh token may already be spent)
            // is exactly what produces "401 Invalid authentication credentials".
            // If the disk file is newer, sync it into the DB instead.
            if (Files.exists(credsFile)) {
                String diskJson = Files.readString(credsFile, StandardCharsets.UTF_8);
                if (extractCredentialExpiry(diskJson) > extractCredentialExpiry(credsJson)) {
                    settings.setClaudeCredentials(diskJson);
                    settingsRepository.save(settings);
                    lastCredentialsMtime = Files.getLastModifiedTime(credsFile);
                    log.info("Disk Claude credentials are newer than the stored copy; "
                            + "kept disk file and updated site_settings");
                    return;
                }
            }
            Files.writeString(credsFile, credsJson, StandardCharsets.UTF_8);
            try {
                Set<PosixFilePermission> perms = PosixFilePermissions.fromString("rw-------");
                Files.setPosixFilePermissions(credsFile, perms);
            } catch (Exception ignored) {
                // non-POSIX filesystem — best effort
            }
            chownToRunAsUser(credsFile);
            lastCredentialsMtime = Files.getLastModifiedTime(credsFile);
            log.info("Materialized stored Claude credentials to {}", credsFile);
        } catch (Exception e) {
            log.warn("Failed to materialize stored Claude credentials: {}", e.getMessage());
        }
    }

    /**
     * Parse the OAuth {@code expiresAt} epoch-millis out of a Claude credentials
     * JSON blob. Returns -1 when absent or unparseable, so a missing/blank
     * credential always sorts before a present one.
     */
    private long extractCredentialExpiry(String credsJson) {
        if (credsJson == null || credsJson.isBlank()) {
            return -1L;
        }
        try {
            JsonNode exp = objectMapper.readTree(credsJson).path("claudeAiOauth").path("expiresAt");
            return exp.isNumber() ? exp.asLong() : -1L;
        } catch (Exception e) {
            return -1L;
        }
    }

    /**
     * After a Claude CLI invocation, the CLI may have silently refreshed and
     * rotated the OAuth tokens in {@code ~/.claude/.credentials.json}. Persist the
     * updated file back to site_settings so it survives a pod restart — otherwise
     * {@link #materializeStoredClaudeCredentials()} restores a stale copy whose
     * refresh token has already been consumed, yielding
     * "401 Invalid authentication credentials". Gated on the file mtime so the
     * common (no-refresh) path never touches the database.
     */
    void persistRefreshedCredentialsIfChanged() {
        try {
            Path credsFile = Path.of(getClaudeCliHome(), ".claude", ".credentials.json");
            if (!Files.exists(credsFile)) {
                return;
            }
            java.nio.file.attribute.FileTime mtime = Files.getLastModifiedTime(credsFile);
            java.nio.file.attribute.FileTime seen = lastCredentialsMtime;
            if (seen != null && mtime.compareTo(seen) <= 0) {
                return;
            }
            String json = Files.readString(credsFile, StandardCharsets.UTF_8);
            if (json.isBlank()) {
                return;
            }
            SiteSettings settings = settingsRepository.findAll().stream().findFirst().orElse(null);
            if (settings != null && !json.equals(settings.getClaudeCredentials())) {
                settings.setClaudeCredentials(json);
                settingsRepository.save(settings);
                log.info("Persisted refreshed Claude credentials to site_settings ({} bytes)", json.length());
            }
            lastCredentialsMtime = mtime;
        } catch (Exception e) {
            log.warn("Failed to persist refreshed Claude credentials: {}", e.getMessage());
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

    /**
     * Persist one Claude CLI invocation — the prompt/command sent and the raw
     * output returned — for the admin Claude Logs page. Best-effort: a logging
     * failure must never break or fail the actual CLI execution.
     */
    private void persistCliLog(long requestId, String operationType, List<String> command,
                               String prompt, String model, String workingDir,
                               String rawOutput, int exitCode, long durationMs) {
        try {
            ClaudeCliLog entry = new ClaudeCliLog();
            entry.setRequestId(requestId);
            entry.setOperationType(operationType);
            entry.setModel((model != null && !model.isBlank()) ? model : "default");
            entry.setWorkingDir(workingDir);
            entry.setCommand(capLogField(maskPromptInCommand(command, prompt)));
            entry.setPrompt(capLogField(prompt));
            entry.setRawOutput(capLogField(rawOutput));
            entry.setExitCode(exitCode);
            entry.setDurationMs(durationMs);
            cliLogRepository.save(entry);
        } catch (Exception e) {
            log.warn("[CLAUDE-REQ-{}] Failed to persist CLI log: {}", requestId, e.getMessage());
        }
    }

    /**
     * Render the CLI command as a single line, replacing the (potentially huge)
     * prompt argument with a {@code <prompt: N chars>} placeholder so the stored
     * command stays readable and the prompt is not duplicated.
     */
    private static String maskPromptInCommand(List<String> command, String prompt) {
        if (command == null) return "";
        StringBuilder sb = new StringBuilder();
        for (String arg : command) {
            if (sb.length() > 0) sb.append(' ');
            if (prompt != null && prompt.equals(arg)) {
                sb.append("<prompt: ").append(prompt.length()).append(" chars>");
            } else {
                sb.append(arg);
            }
        }
        return sb.toString();
    }

    private static String capLogField(String s) {
        if (s == null) return null;
        return s.length() > MAX_CLI_LOG_FIELD
                ? s.substring(0, MAX_CLI_LOG_FIELD) + "\n…(truncated, " + s.length() + " chars total)"
                : s;
    }

    private void chownToRunAsUser(Path path) {
        chownToRunAsUser(path.toString());
    }

    /**
     * Chown {@code pathStr} to {@code claudeRunAsUser:claudeRunAsUser} when running
     * as a different OS user. Returns true on success (or when no chown is needed),
     * false on any failure — including a non-zero exit code from {@code chown}, which
     * the previous implementation silently swallowed. Captures and logs the chown
     * stderr at WARN level so silent failures (e.g. the user not existing, the
     * filesystem refusing ownership changes, AppArmor/SELinux denials) become
     * visible instead of surfacing only as git's generic
     * "Could not read from remote repository."
     */
    private boolean chownToRunAsUser(String pathStr) {
        if (!shouldRunAsDifferentUser()) {
            return true;
        }
        // Skip the chown when the path is already owned by the target user.
        // ensureUserExists() chowns the entire workspace once at startup, so most
        // subsequent paths are already correctly owned and chowning them is
        // redundant. In hardened containers where CAP_CHOWN is dropped, the
        // redundant call would otherwise fail with EPERM ("Operation not
        // permitted") and surface a misleading WARN even though ownership is
        // already correct.
        try {
            java.nio.file.attribute.UserPrincipal owner = Files.getOwner(Path.of(pathStr));
            if (owner != null && claudeRunAsUser.equals(owner.getName())) {
                return true;
            }
        } catch (Exception ignored) {
            // Could not read ownership (non-POSIX FS, path missing, etc.) —
            // fall through and attempt the chown.
        }
        try {
            ProcessBuilder chown = new ProcessBuilder(
                    "chown", claudeRunAsUser + ":" + claudeRunAsUser, pathStr);
            chown.redirectErrorStream(true);
            Process p = chown.start();
            String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            int exit = p.waitFor();
            if (exit != 0) {
                // "Operation not permitted" (EPERM) is the chown error when the
                // process lacks CAP_CHOWN — the standard hardening posture for
                // containers where the workspace volume is pre-mounted with the
                // correct ownership/permissions by the orchestrator. In that
                // case the chown is a redundant best-effort step: the run-as
                // user can already read/write the path, so the failure is
                // benign and the surrounding operation (git clone, credential
                // materialization, etc.) succeeds. Logging it at WARN with the
                // generic "chown failed" template makes it indistinguishable
                // from real ownership problems (missing user, bad path) and
                // trips auto-fix tooling into churning fix attempts on an
                // expected configuration state. Demote the EPERM case to INFO
                // so genuine failures still surface at WARN.
                boolean isCapChownDropped = output.contains("Operation not permitted");
                if (isCapChownDropped) {
                    log.info("chown {} -> {}:{} skipped: container lacks CAP_CHOWN "
                                    + "(expected in hardened deployments where the workspace "
                                    + "volume is pre-mounted with correct ownership)",
                            pathStr, claudeRunAsUser, claudeRunAsUser);
                } else {
                    log.warn("chown {} -> {}:{} failed (exit {}): {}",
                            pathStr, claudeRunAsUser, claudeRunAsUser, exit,
                            output.isEmpty() ? "(no output)" : output);
                }
                return false;
            }
            return true;
        } catch (Exception e) {
            log.warn("chown {} -> {} failed: {}", pathStr, claudeRunAsUser, e.getMessage());
            return false;
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
                && isProcessRunningAsRoot();
    }

    private volatile Boolean cachedIsRoot;

    /**
     * Detect whether the current JVM process is effectively running as root.
     * Checks {@code user.name} first, then falls back to {@code id -u} so we
     * still detect root in containers where the JVM's {@code user.name} system
     * property doesn't reflect the actual effective uid. Result is cached
     * because uid does not change during process lifetime.
     */
    private boolean isProcessRunningAsRoot() {
        Boolean cached = cachedIsRoot;
        if (cached != null) {
            return cached;
        }
        if ("root".equals(System.getProperty("user.name"))) {
            cachedIsRoot = Boolean.TRUE;
            return true;
        }
        try {
            ProcessBuilder pb = new ProcessBuilder("id", "-u");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            int exit = p.waitFor();
            boolean isRoot = exit == 0 && "0".equals(output);
            cachedIsRoot = isRoot;
            return isRoot;
        } catch (Exception e) {
            log.debug("Could not determine effective uid via `id -u`: {}", e.getMessage());
            cachedIsRoot = Boolean.FALSE;
            return false;
        }
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
        List<String> effective = injectGitSafeDirectory(command);
        if (!shouldRunAsDifferentUser()) {
            return effective;
        }
        List<String> wrapped = new java.util.ArrayList<>();
        wrapped.add("runuser");
        wrapped.add("-u");
        wrapped.add(claudeRunAsUser);
        wrapped.add("--");
        wrapped.addAll(effective);
        return wrapped;
    }

    /**
     * When the command is a {@code git} invocation, inject
     * {@code -c safe.directory=*} so git does not refuse to operate on
     * repositories whose directory ownership differs from the current uid
     * (e.g. when the workspace was created by root but git runs as the
     * configured run-as user via {@code runuser}).
     */
    private List<String> injectGitSafeDirectory(List<String> command) {
        if (command == null || command.isEmpty()) {
            return command;
        }
        String first = command.get(0);
        if (first == null) {
            return command;
        }
        // Match bare "git" or any path ending in "/git".
        boolean isGit = first.equals("git") || first.endsWith("/git");
        if (!isGit) {
            return command;
        }
        List<String> out = new java.util.ArrayList<>(command.size() + 2);
        out.add(first);
        out.add("-c");
        out.add("safe.directory=*");
        for (int i = 1; i < command.size(); i++) {
            out.add(command.get(i));
        }
        return out;
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

    /**
     * Snapshot of the Claude CLI request queue for the admin Claude Queue page:
     * the configured rate/concurrency limits, the current rate-limit window
     * usage, and every in-flight request keyed by phase.
     */
    public Map<String, Object> getClaudeQueueSnapshot() {
        long now = System.currentTimeMillis();

        List<Map<String, Object>> requests = new java.util.ArrayList<>();
        for (QueueEntry e : requestRegistry.values()) {
            Map<String, Object> r = new java.util.LinkedHashMap<>();
            r.put("requestId", e.requestId);
            r.put("operationType", e.operationType);
            r.put("model", e.model);
            r.put("sessionId", e.sessionId);
            r.put("workingDir", e.workingDir);
            r.put("phase", e.phase);
            r.put("enqueuedAt", e.enqueuedAt);
            r.put("phaseChangedAt", e.phaseChangedAt);
            r.put("ageMs", now - e.enqueuedAt);
            r.put("phaseAgeMs", now - e.phaseChangedAt);
            requests.add(r);
        }
        // Newest first
        requests.sort((a, b) -> Long.compare((long) b.get("enqueuedAt"), (long) a.get("enqueuedAt")));

        long running = requests.stream().filter(r -> QueueEntry.PHASE_RUNNING.equals(r.get("phase"))).count();
        long awaitingConcurrency = requests.stream()
                .filter(r -> QueueEntry.PHASE_AWAITING_CONCURRENCY.equals(r.get("phase"))).count();
        long awaitingRateLimit = requests.stream()
                .filter(r -> QueueEntry.PHASE_AWAITING_RATE_LIMIT.equals(r.get("phase"))).count();

        Map<String, Object> snap = new java.util.LinkedHashMap<>();
        snap.put("now", now);
        snap.put("maxConcurrent", claudeMaxConcurrent);
        snap.put("maxCallsPerMinute", claudeMaxCallsPerMinute);
        snap.put("availablePermits", claudeGate.availablePermits());
        snap.put("rateLimitWindowUsed", currentRateLimitWindowUsage(now));
        snap.put("running", running);
        snap.put("awaitingConcurrency", awaitingConcurrency);
        snap.put("awaitingRateLimit", awaitingRateLimit);
        snap.put("requests", requests);
        return snap;
    }

    /**
     * Store the parsed cost data for {@code sessionId}, replacing any prior entry.
     * Package-private so unit tests can seed values without running a CLI call.
     */
    void recordSessionCost(String sessionId, ClaudeCostInfo cost) {
        if (sessionId == null || cost == null) {
            return;
        }
        sessionCosts.put(sessionId, cost);
    }

    /**
     * Consume the cost record produced by the most recent CLI call for
     * {@code sessionId}. Returns {@code null} when no cost has been recorded
     * (e.g. the CLI call failed before emitting a parseable envelope).
     * Removes the entry so subsequent calls see {@code null} — each cost is
     * intended to be persisted exactly once.
     */
    public ClaudeCostInfo pollSessionCost(String sessionId) {
        if (sessionId == null) {
            return null;
        }
        return sessionCosts.remove(sessionId);
    }

    /** Number of timestamps in the rate-limit circular buffer that fall within the last 60s. */
    private int currentRateLimitWindowUsage(long now) {
        rateLimitLock.lock();
        try {
            long cutoff = now - 60_000L;
            int count = 0;
            for (long ts : callTimestamps) {
                if (ts > cutoff) count++;
            }
            return count;
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
        // Route through sendToClaude so this respects the rate/concurrency gates
        // and appears in the admin Claude Queue page like every other CLI call.
        return sendToClaude(prependManagedFoldersScope(prompt), sessionId, getMainRepoDir(),
                null, null, "recommendations", model, 0);
    }

    /**
     * Prepend the {@link #buildManagedFoldersScope managed-folders scope block}
     * to a prompt. No-op when no scope is configured.
     */
    String prependManagedFoldersScope(String prompt) {
        String scope = buildManagedFoldersScope();
        return scope.isEmpty() ? prompt : scope + prompt;
    }

    /**
     * Build the "scope restriction" text that pins the AI to the admin-configured
     * managed folders in {@link SiteSettings#getManagedFolders()}. Returns an
     * empty string when no folders are configured, so the historic
     * whole-repo behaviour is preserved.
     */
    String buildManagedFoldersScope() {
        SiteSettings settings = getSettings();
        String raw = settings != null ? settings.getManagedFolders() : null;
        if (raw == null || raw.isBlank()) {
            return "";
        }
        List<String> folders = new java.util.ArrayList<>();
        for (String line : raw.split("\\R")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                folders.add(trimmed);
            }
        }
        if (folders.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("=== SCOPE RESTRICTION ===\n");
        sb.append("You may ONLY read, modify, create, rename, or delete files within these folders ");
        sb.append("(paths are relative to the repository root):\n");
        for (String f : folders) {
            sb.append("  - ").append(f).append("\n");
        }
        sb.append("Treat all other folders as read-only context: you may consult them to understand ");
        sb.append("the codebase, but you MUST NOT change anything outside the folders listed above. ");
        sb.append("If a task seems to require changes outside this scope, do NOT proceed — mark the ");
        sb.append("task FAILED with a clear explanation of which out-of-scope path it would touch.\n");
        sb.append("=========================\n\n");
        return sb.toString();
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
        return prependManagedFoldersScope(String.format(
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
        ));
    }

    public CompletableFuture<String> continueConversation(String sessionId, String userMessage,
                                                           String conversationContext,
                                                           String workingDir,
                                                           Consumer<String> progressCallback) {
        return sendToClaudeAsync(userMessage, sessionId, workingDir, conversationContext, progressCallback, "continue", resolveModel(), 0);
    }

    /**
     * Apply an AI expert's critique to the current plan via the MAIN AI model.
     * The expert reviews are advisory: they identify what's missing or wrong
     * (analysis + proposedChanges), and this call asks the main model to
     * author the revised plan. Keeps the main model as the sole author of
     * plan content, so the plan voice / style stays consistent across
     * iterations. Returns JSON with {plan, planDisplaySummary} on success
     * or {status:"FAILED", message:"..."} on failure.
     */
    public CompletableFuture<String> applyExpertFeedbackToPlan(String suggestionTitle,
                                                                String suggestionDescription,
                                                                String currentLowLevelPlan,
                                                                String currentHighLevelPlan,
                                                                String expertDisplayName,
                                                                String expertAnalysis,
                                                                String expertProposedChanges,
                                                                String workingDir,
                                                                Consumer<String> progressCallback) {
        String prompt = prependManagedFoldersScope(String.format(
                "You are the main planning model and you own the plan for this suggestion. " +
                "An AI expert (%s) just reviewed the current plan and recommended changes. " +
                "Your job is to incorporate those recommendations and produce a REVISED plan. " +
                "You are NOT writing the recommendations as-is — you decide how best to fold them " +
                "into the existing plan while preserving everything the user still needs.\n\n" +
                "Suggestion Title: %s\n" +
                "Suggestion Description: %s\n\n" +
                "CURRENT low-level plan:\n%s\n\n" +
                "CURRENT high-level plan summary:\n%s\n\n" +
                "Expert analysis from %s:\n%s\n\n" +
                "Expert proposed changes:\n%s\n\n" +
                "RULES:\n" +
                "- Produce a COMPLETE revised plan covering ALL work needed to implement the " +
                "suggestion end-to-end. Do not drop scope unless the expert explicitly asked.\n" +
                "- Both layers must be present and differ meaningfully: the low-level plan can " +
                "reference files/classes/frameworks, the high-level plan is plain language with no " +
                "technical terms.\n" +
                "- If the expert's recommendation conflicts with the user's original request, prefer " +
                "the user's request and explain in your message.\n" +
                "- DO NOT include a tasks list — tasks are generated separately after the plan is approved.\n" +
                "- DO NOT ask clarification questions in this response. If the expert feedback is " +
                "unactionable, return the current plan unchanged and explain in 'message'.\n\n" +
                "Respond with exactly this JSON shape — nothing outside the JSON:\n" +
                "{\"status\": \"PLAN_REVISED\", " +
                "\"message\": \"plain-language summary of how you incorporated the expert's feedback\", " +
                "\"plan\": \"complete revised low-level technical plan\", " +
                "\"planDisplaySummary\": \"complete revised high-level plain-language summary\"}",
                expertDisplayName == null ? "an expert" : expertDisplayName,
                suggestionTitle == null ? "" : suggestionTitle,
                suggestionDescription == null ? "" : suggestionDescription,
                currentLowLevelPlan == null ? "(no current plan)" : currentLowLevelPlan,
                currentHighLevelPlan == null ? "(no high-level summary)" : currentHighLevelPlan,
                expertDisplayName == null ? "an expert" : expertDisplayName,
                expertAnalysis == null ? "(no analysis provided)" : expertAnalysis,
                expertProposedChanges == null ? "(no proposed changes text)" : expertProposedChanges));

        return sendToClaudeAsync(prompt, null, workingDir, null, progressCallback,
                "apply-expert-feedback", resolveModel(), 0);
    }

    /**
     * Generate the implementation task list from a fully-reviewed plan. Runs
     * AFTER every expert review has converged so the experts only ever see
     * the plan (never tasks). Uses the main AI model (resolveModel) — never
     * the expert model — because task generation is creative work, not
     * review. Caller is expected to feed the response to
     * {@code PlanExecutionService.savePlanTasks}.
     */
    public CompletableFuture<String> generateTasksFromPlan(String suggestionTitle,
                                                            String suggestionDescription,
                                                            String planSummary,
                                                            String planDisplaySummary,
                                                            String workingDir,
                                                            Consumer<String> progressCallback) {
        String prompt = prependManagedFoldersScope(String.format(
                "The implementation plan below has already been generated and approved by every expert " +
                "reviewer. Your only job is to break the plan into an ordered list of concrete, actionable " +
                "implementation tasks.\n\n" +
                "Suggestion Title: %s\n" +
                "Suggestion Description: %s\n\n" +
                "Approved low-level plan:\n%s\n\n" +
                "Approved high-level summary:\n%s\n\n" +
                "TASK GENERATION RULES:\n" +
                "- Cover ALL work needed to implement the plan end-to-end — nothing more, nothing less.\n" +
                "- Each task must be a concrete, actionable unit of work.\n" +
                "- Order tasks by implementation sequence (dependencies first).\n" +
                "- Provide a realistic time estimate in minutes for each task.\n" +
                "- Typically 3-10 tasks; use more only if the plan genuinely needs them.\n\n" +
                "DUAL-LEVEL DETAIL RULES:\n" +
                "- Every task has TWO layers and you MUST emit BOTH:\n" +
                "  * LOW-LEVEL ('title' / 'description'): technical detail for an expert reviewer or executor. " +
                "Reference specific files, classes, methods, modules, frameworks, APIs, or schemas where relevant.\n" +
                "  * HIGH-LEVEL ('displayTitle' / 'displayDescription'): plain non-technical language for the user. " +
                "Describe the outcome or feature change, never file/class/framework names.\n" +
                "- The two layers must differ meaningfully — do not copy the same text into both.\n\n" +
                "Respond with exactly this JSON shape — no preamble, no explanation outside the JSON:\n" +
                "{\"status\": \"TASKS_READY\", " +
                "\"message\": \"brief plain-language summary of the task list for the user\", " +
                "\"tasks\": [\n" +
                "  {\"title\": \"low-level technical task name\", " +
                "\"description\": \"detailed technical description of the work\", " +
                "\"displayTitle\": \"high-level user-facing task name in plain language\", " +
                "\"displayDescription\": \"plain-language description of the outcome\", " +
                "\"estimatedMinutes\": number},\n" +
                "  ...\n" +
                "]}",
                suggestionTitle == null ? "" : suggestionTitle,
                suggestionDescription == null ? "" : suggestionDescription,
                planSummary == null ? "" : planSummary,
                planDisplaySummary == null ? "" : planDisplaySummary));

        return sendToClaudeAsync(prompt, null, workingDir, null, progressCallback,
                "generate-tasks", resolveModel(), 0);
    }

    public CompletableFuture<String> executePlan(String sessionId, String plan, String tasksJson,
                                                  String workingDir,
                                                  Consumer<String> progressCallback) {
        String prompt = prependManagedFoldersScope(String.format(
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
        ));

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
        String prompt = prependManagedFoldersScope(String.format(
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
        ));

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
        String prompt = prependManagedFoldersScope(String.format(
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
        ));

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
        return prependManagedFoldersScope(String.format(
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
                "PLAN-ONLY REVIEW:\n" +
                "- At this stage you are reviewing the PLAN ONLY. Tasks have NOT been created yet — they will be " +
                "generated later by the main AI model from the final approved plan.\n" +
                "- Do NOT include any 'revisedTasks' or 'lockedTaskIndices' fields — they will be ignored.\n" +
                "- If the plan is missing essential work, demand it be added to the plan text via revisedPlan.\n\n" +
                "Respond in this JSON format:\n" +
                "If the plan fully and faithfully captures the original suggestion:\n" +
                "{\"status\": \"APPROVED\", \"analysis\": \"what you verified and found to be complete\", " +
                "\"message\": \"concise NON-TECHNICAL summary for the user\"}\n\n" +
                "If the plan is missing content, reduces scope, or misrepresents the original request:\n" +
                "{\"status\": \"CHANGES_PROPOSED\", " +
                "\"analysis\": \"what is missing, incorrect, or off-track in the current plan\", " +
                "\"proposedChanges\": \"specific, actionable description of what should be added, removed, " +
                "or modified — be concrete about WHAT needs to change and WHY\", " +
                "\"message\": \"concise NON-TECHNICAL summary for the user\"}\n\n" +
                "IMPORTANT: You are advisory. DO NOT write the revised plan yourself — the main planning AI rewrites " +
                "the plan from your analysis and proposedChanges. So focus on the critique, not on producing replacement text. " +
                "Tasks have NOT been created yet and you MUST NOT propose any task list. " +
                "Do not include 'revisedPlan', 'revisedPlanDisplaySummary', 'revisedTasks', or 'lockedTaskIndices' — they will be ignored.",
                expertPrompt,
                suggestionTitle,
                suggestionDescription,
                plan,
                tasksJson != null ? "Current Tasks:\n" + tasksJson + "\n\n" : "",
                previousNotes != null && !previousNotes.isBlank() ?
                        "Previous expert reviews:\n" + previousNotes + "\n\n" : ""
        ));
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

        return prependManagedFoldersScope(String.format(
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
                "{\"status\": \"CHANGES_PROPOSED\", " +
                "\"analysis\": \"your technical analysis of the issues found\", " +
                "\"proposedChanges\": \"specific, actionable description of what should change in the plan — " +
                "be concrete about WHAT to add/remove/modify and WHY\", " +
                "\"message\": \"concise NON-TECHNICAL summary for the user\"}\n\n" +
                "If you need the user to answer questions before you can complete your review:\n" +
                "{\"status\": \"NEEDS_CLARIFICATION\", \"analysis\": \"what you've found so far\", " +
                "\"questions\": [\"high-level non-technical question 1\", \"high-level non-technical question 2\"], " +
                "\"message\": \"brief summary of what you need to know\"}\n\n" +
                "IMPORTANT: You are advisory. DO NOT write the revised plan yourself — the main planning AI rewrites " +
                "the plan from your analysis and proposedChanges. So focus on the critique, not on producing replacement text. " +
                "Tasks have NOT been created yet and you MUST NOT propose any task list. " +
                "Do not include 'revisedPlan', 'revisedPlanDisplaySummary', or 'revisedTasks' — they will be ignored. " +
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
        ));
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
        // Assign the requestId up front so the admin Claude Queue page can see
        // this call while it waits on the rate-limit / concurrency gates, and
        // so the [CLAUDE-REQ-N] log prefix is consistent from entry to exit.
        long requestId = requestCounter.incrementAndGet();
        QueueEntry entry = new QueueEntry(requestId, operationType, model, sessionId, workingDir,
                QueueEntry.PHASE_AWAITING_RATE_LIMIT);
        requestRegistry.put(requestId, entry);
        try {
            acquireRateLimit();

            entry.setPhase(QueueEntry.PHASE_AWAITING_CONCURRENCY);
            claudeGate.acquire();
            try {
                entry.setPhase(QueueEntry.PHASE_RUNNING);
                return sendToClaudeGated(prompt, sessionId, workingDir, conversationContext,
                        progressCallback, operationType, model, maxTurns, requestId);
            } finally {
                claudeGate.release();
            }
        } finally {
            requestRegistry.remove(requestId);
        }
    }

    private String sendToClaudeGated(String prompt, String sessionId, String workingDir,
                                 String conversationContext,
                                 Consumer<String> progressCallback,
                                 String operationType, String model, int maxTurns,
                                 long requestId) throws Exception {
        String logPrefix = String.format("[CLAUDE-REQ-%d][%s]", requestId, operationType);

        // Pull the freshest credentials from site_settings before executing.
        // The CLI rotates single-use OAuth refresh tokens, and the DB copy may
        // have been refreshed since this pod started (by a re-login or another
        // pod). Running with a stale disk copy whose refresh token was already
        // spent yields "401 Invalid authentication credentials".
        // materializeStoredClaudeCredentials() keeps the newer of disk/DB, so
        // this is a no-op when the disk copy is already current.
        materializeStoredClaudeCredentials();

        ClaudeExecutionException lastException = null;
        try {
            for (int attempt = 1; attempt <= claudeMaxRetries + 1; attempt++) {
                try {
                    return executeClaudeProcess(prompt, sessionId, workingDir, conversationContext,
                            progressCallback, operationType, model, maxTurns, logPrefix, requestId);
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
        } finally {
            // The CLI may have refreshed/rotated the OAuth tokens on disk during
            // this call — capture them so a pod restart doesn't revert to a stale copy.
            persistRefreshedCredentialsIfChanged();
        }
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
                                        String logPrefix, long requestId) throws Exception {
        long startTime = System.currentTimeMillis();

        // Expert reviews and feedback always get a fresh session (no resume)
        boolean isExpertOp = operationType.startsWith("expert-review:") || operationType.startsWith("review-feedback:");
        String cliSessionId = (isExpertOp || sessionId == null) ? null : sessionMap.get(sessionId);
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
            persistCliLog(requestId, operationType, command, prompt, model, workingDir,
                    output.toString().trim() + "\n(process killed — timed out)", -1, elapsed);
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

        // Persist the full prompt/command/output for the admin Claude Logs page.
        persistCliLog(requestId, operationType, command, prompt, model, workingDir,
                rawOutput, exitCode, elapsed);

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

                // Capture cost/usage so callers (e.g. expert reviews) can
                // persist what this call cost. Stored even on error envelopes
                // so partial usage is not lost.
                recordSessionCost(sessionId, ClaudeCostInfo.fromCliJson(root, model));

                // Check for error responses (e.g., dead session)
                if (root.has("is_error") && root.get("is_error").asBoolean()) {
                    String errorMsg = root.has("result") ? root.get("result").asText() : rawOutput;
                    if (isResume && isDeadSessionError(errorMsg)) {
                        log.warn("{} dead session detected, rebuilding context", logPrefix);
                        return handleDeadSession(prompt, sessionId, workingDir,
                                conversationContext, progressCallback, operationType, model, maxTurns);
                    }
                    // "Not logged in · Please run /login" is an operator-actionable
                    // configuration state, not a CLI failure: PlanExecutionService
                    // detects the same message and parks the task as PENDING with a
                    // "Waiting for Claude CLI login by an admin" detail. Logging it at
                    // WARN with the generic "error response" template makes it
                    // indistinguishable from real CLI failures and trips auto-fix
                    // tooling into churning fix attempts on a config issue. Demote it
                    // to INFO with a clearer message; the exception still propagates
                    // so the downstream pause logic runs unchanged.
                    if (isNotLoggedInError(errorMsg)) {
                        log.info("{} Claude CLI is not logged in — pausing this call; "
                                + "an admin can re-login from the Settings page to resume",
                                logPrefix);
                    } else {
                        log.warn("{} error response: {}", logPrefix, truncate(errorMsg, MAX_LOG_RESPONSE_LENGTH));
                    }
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
        if (sessionId != null && parsedCliSessionId != null && !parsedCliSessionId.isBlank()) {
            sessionMap.put(sessionId, parsedCliSessionId);
            log.debug("{} stored CLI session: {}", logPrefix, parsedCliSessionId);
        }

        if (exitCode != 0) {
            String outputSnippet = truncate(rawOutput, MAX_LOG_RESPONSE_LENGTH);
            log.warn("{} non-zero exit code: {} output: {}", logPrefix, exitCode, outputSnippet);
            ClaudeFailureType failureType = classifyFailure(rawOutput, exitCode, null);
            String detail = (rawOutput == null || rawOutput.isBlank())
                    ? "(no output)"
                    : truncate(rawOutput, 500);
            throw new ClaudeExecutionException(
                    "Claude CLI exited with code " + exitCode + ": " + detail,
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

    /**
     * "Not logged in · Please run /login" is the CLI's response when the stored
     * OAuth credentials are missing, expired, or have a revoked refresh token.
     * It is an operator-actionable configuration state, not a CLI bug — callers
     * (PlanExecutionService) detect it via the same substrings to pause work
     * gracefully until an admin re-logs in.
     *
     * <p>The CLI may also surface the same underlying condition as
     * "Failed to authenticate. API Error: 401 Invalid authentication credentials"
     * when the OAuth refresh token rotates between pods or has already been
     * spent. The remediation is identical (admin re-login), so we treat the
     * 401 variant as the same operator-actionable state.
     */
    boolean isNotLoggedInError(String output) {
        if (output == null) return false;
        String lower = output.toLowerCase();
        return lower.contains("not logged in")
                || lower.contains("please run /login")
                || lower.contains("invalid authentication credentials")
                || (lower.contains("failed to authenticate") && lower.contains("401"));
    }

    private String handleDeadSession(String prompt, String sessionId, String workingDir,
                                      String conversationContext,
                                      Consumer<String> progressCallback,
                                      String operationType, String model, int maxTurns) throws Exception {
        log.warn("[CLAUDE-{}] session={} is dead, starting fresh session with conversation context",
                operationType, sessionId);
        if (sessionId != null) {
            sessionMap.remove(sessionId);
        }

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
        // Buffer git's stdout/stderr instead of streaming it at INFO. The output
        // includes lines like " * [new branch] <name> -> origin/<name>", and remote
        // branch names can contain substrings like "error" / "exception" / "failed"
        // that trip keyword-based log-monitoring systems even when the pull
        // succeeds. We replay the captured lines at WARN only when the pull
        // actually fails, so diagnostic value is preserved without false alarms.
        List<String> capturedOutput = new java.util.ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                capturedOutput.add(line);
                log.debug("Git pull main-repo: {}", line);
            }
        }

        int exitCode = process.waitFor();
        if (exitCode != 0) {
            for (String line : capturedOutput) {
                log.warn("Git pull main-repo: {}", line);
            }
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

    /**
     * Run a git command in {@code repoDir} as the configured run-as user (when running
     * as root). Used by callers outside this service so their git operations don't leave
     * files in {@code .git/} owned by root, which would later break commands invoked via
     * {@code runuser} (e.g. {@code git pull} failing with
     * "cannot open '.git/FETCH_HEAD': Permission denied").
     */
    public void runGitCommandAsUser(String repoDir, String... command) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(wrapCommandForUser(java.util.List.of(command)));
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
            }
        }

        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new RuntimeException(
                    "Git command failed (" + String.join(" ", command) + "): " + output.toString().trim());
        }
    }

    /**
     * Run a git command in {@code repoDir} as the configured run-as user and return its
     * exit code without throwing on non-zero. Used for probes like checking whether a
     * branch exists.
     */
    public int runGitCommandAsUserExitCode(String repoDir, String... command) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(wrapCommandForUser(java.util.List.of(command)));
        pb.directory(new File(repoDir));
        pb.redirectErrorStream(true);
        pb.redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")));
        applyGitEnvironment(pb);

        Process process = pb.start();
        consumeStream(process.getInputStream());
        return process.waitFor();
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
        try {
            return doGitClone(repoUrl, targetDir);
        } catch (RuntimeException e) {
            String msg = String.valueOf(e.getMessage());
            // "fatal: unable to write new index file" is git's generic failure when it
            // cannot rename .git/index.lock onto .git/index — almost always caused by
            // transient disk pressure on the workspace volume. The most common source
            // here is the .trash-<uuid>/ siblings that evictStaleWorkspaceDir leaves
            // behind: rename is instant but the actual deletion runs on a daemon
            // thread, so if a fresh clone is kicked off while a previous trash tree
            // (full node_modules / .gradle / build artifacts) is still being torn
            // down, the workspace can be temporarily out of space or inodes when
            // git tries to commit its index. Wait for outstanding cleanups to drain,
            // re-evict any partial clone, and retry once before surfacing the
            // failure.
            if (!msg.contains("unable to write new index file")) {
                throw e;
            }
            log.warn("Git clone failed with transient index-write error; "
                    + "waiting for pending workspace cleanups and retrying once: {}", msg);
            waitForPendingWorkspaceDeletes(60_000L);
            File partial = new File(targetDir);
            if (partial.exists()) {
                try {
                    // Remove the partial clone *synchronously* (rm -rf inline) — using
                    // evictStaleWorkspaceDir() here would atomic-rename it to
                    // .trash-<uuid>/ and schedule yet another background delete, whose
                    // rm -rf I/O would then thrash the workspace volume in parallel with
                    // the retry clone. That is the very disk-pressure condition that
                    // caused git's index write to fail in the first place — recreating
                    // it inside the retry makes the retry far more likely to fail too.
                    removeDirInline(partial.toPath());
                } catch (Exception cleanupErr) {
                    log.warn("Could not remove partial clone {} before retry: {}",
                            targetDir, cleanupErr.getMessage());
                }
            }
            return doGitClone(repoUrl, targetDir);
        }
    }

    /** Background trash-delete threads spawned by {@link #scheduleBackgroundDelete}. */
    private final java.util.concurrent.CopyOnWriteArrayList<Thread> pendingWorkspaceDeletes =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    /**
     * Block until every currently-pending background workspace delete has either
     * finished or {@code timeoutMs} has elapsed across the wait as a whole.
     * Used by {@link #gitClone} to recover disk space pressure before retrying
     * a clone that died with "unable to write new index file".
     */
    private void waitForPendingWorkspaceDeletes(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        for (Thread t : pendingWorkspaceDeletes) {
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) {
                log.warn("Timed out waiting for background workspace cleanups to finish");
                return;
            }
            try {
                t.join(remaining);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /**
     * Synchronous {@code rm -rf} used by the {@link #gitClone} retry path so the
     * disk is fully quiet before the retry clone starts. Unlike
     * {@link #scheduleBackgroundDelete}, this blocks the caller until the tree
     * is gone — exactly the contract the retry needs.
     */
    private void removeDirInline(Path path) throws Exception {
        long start = System.currentTimeMillis();
        ProcessBuilder pb = new ProcessBuilder("rm", "-rf", path.toString());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        consumeStream(p.getInputStream());
        int rc = p.waitFor();
        long elapsed = System.currentTimeMillis() - start;
        if (rc == 0) {
            log.info("Inline removal of {} completed in {}ms", path, elapsed);
        } else {
            log.warn("Inline removal of {} failed (rc={}, elapsed={}ms)", path, rc, elapsed);
        }
    }

    private String doGitClone(String repoUrl, String targetDir) throws Exception {
        long startTime = System.currentTimeMillis();

        File dir = new File(targetDir);
        // Ensure the parent (workspace) directory exists and is owned by the
        // run-as user. ensureUserExists() chowns the workspace once at
        // @PostConstruct, but only if it already existed at that point —
        // in containers where /workspace is a volume mounted/created after
        // startup (or simply never pre-created), git clone here would fail
        // with "could not create work tree dir: Permission denied" because
        // the run-as user can't mkdir under a root-owned parent.
        File parent = dir.getParentFile();
        if (parent != null) {
            if (!parent.exists()) {
                if (!parent.mkdirs() && !parent.exists()) {
                    log.warn("Failed to create workspace parent dir: {}", parent);
                } else {
                    log.info("Created workspace parent dir: {}", parent);
                }
            }
            chownToRunAsUser(parent.getPath());
        }
        if (dir.exists()) {
            // Atomically rename the stale dir out of the way and delete it on a
            // background thread. A recursive rm -rf of a tree that has had
            // node_modules / .gradle / build artifacts accumulated by prior
            // Claude runs is one syscall per file and can hang for many minutes,
            // blocking the clone for no good reason. Rename is one syscall on
            // the same filesystem (near-instant regardless of tree size) and
            // leaves the background thread free to take however long the
            // actual deletion needs.
            evictStaleWorkspaceDir(dir.toPath());
        }

        String sshRepoUrl = toSshUrl(repoUrl);

        // Shallow, single-branch, tag-less clone — the workflow only needs the
        // tip of the default branch to base a suggestion branch on, never older
        // history or other branches. This turns a multi-minute full clone of a
        // large repo into a few-seconds transfer. --progress forces git to emit
        // progress over the (non-TTY) pipe so the log shows the clone advancing
        // instead of going silent for the whole duration.
        List<String> command = java.util.List.of(
                "git", "clone",
                "--depth=1",
                "--single-branch",
                "--no-tags",
                "--progress",
                sshRepoUrl, targetDir);
        ProcessBuilder pb = new ProcessBuilder(wrapCommandForUser(command));
        pb.redirectErrorStream(true);
        pb.redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")));

        applyGitEnvironment(pb);

        log.info("Starting git clone: {} -> {}", sshRepoUrl, targetDir);
        Process process = pb.start();

        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
                // git's "fatal: unable to write new index file" is a known transient
                // failure on this workspace (sibling .trash-<uuid>/ cleanups starving
                // disk during the index write) — gitClone() catches it and retries.
                // Logging it per-line at INFO surfaces a phantom service error to
                // log-scraping tooling even when the retry succeeds and the overall
                // operation completes fine. Downgrade just that line so the INFO
                // stream reflects actual outcomes; the full output is still captured
                // in `output` and propagated in the exception when the retry also
                // fails.
                if (line.contains("fatal: unable to write new index file")) {
                    log.debug("Git clone (transient, will retry): {}", line);
                } else {
                    log.info("Git clone: {}", line);
                }
            }
        }

        int exitCode = process.waitFor();
        long elapsed = System.currentTimeMillis() - startTime;
        if (exitCode != 0) {
            String trimmed = output.toString().trim();
            String suffix = trimmed.isEmpty() ? "" : ": " + trimmed;
            throw new RuntimeException(
                    "Failed to clone repository " + sshRepoUrl + " after " + elapsed
                            + "ms: exit code " + exitCode + suffix);
        }

        log.info("Repository cloned to {} in {}ms (shallow, depth=1, single-branch)",
                targetDir, elapsed);
        markDirectoryTrusted(targetDir);
        return targetDir;
    }

    /**
     * Rename {@code dir} to a sibling {@code .trash-<uuid>} path and delete the
     * renamed copy on a background daemon thread. Used by {@link #gitClone} to
     * keep huge accumulated trees (node_modules / build / .gradle) from
     * blocking the new clone — rename is atomic on the same filesystem, so the
     * caller is unblocked in milliseconds regardless of tree size.
     *
     * <p>Falls back to an inline {@code rm -rf} if the rename can't be done
     * atomically (e.g. cross-filesystem) so the call still succeeds, just slowly.
     */
    private void evictStaleWorkspaceDir(Path dir) throws Exception {
        Path trash = dir.resolveSibling(dir.getFileName().toString() + ".trash-" + UUID.randomUUID());
        long renameStart = System.currentTimeMillis();
        try {
            Files.move(dir, trash, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            log.info("Renamed stale {} -> {} in {}ms (background cleanup queued)",
                    dir, trash, System.currentTimeMillis() - renameStart);
            scheduleBackgroundDelete(trash);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            log.warn("Atomic rename of {} not supported on this filesystem ({}); falling back to inline rm -rf",
                    dir, e.getMessage());
            long rmStart = System.currentTimeMillis();
            ProcessBuilder cleanup = new ProcessBuilder("rm", "-rf", dir.toString());
            cleanup.start().waitFor();
            log.info("Removed {} (inline) in {}ms", dir, System.currentTimeMillis() - rmStart);
        }
    }

    /**
     * Delete {@code path} on a background daemon thread via {@code rm -rf}.
     * Logs the elapsed time so cleanup pressure on workspace storage stays
     * visible without blocking any caller.
     */
    private void scheduleBackgroundDelete(Path path) {
        final Thread[] holder = new Thread[1];
        Thread t = new Thread(() -> {
            long start = System.currentTimeMillis();
            try {
                ProcessBuilder pb = new ProcessBuilder("rm", "-rf", path.toString());
                pb.redirectErrorStream(true);
                Process p = pb.start();
                consumeStream(p.getInputStream());
                int rc = p.waitFor();
                long elapsed = System.currentTimeMillis() - start;
                if (rc == 0) {
                    log.info("Background cleanup of {} completed in {}ms", path, elapsed);
                } else {
                    log.warn("Background cleanup of {} failed (rc={}, elapsed={}ms)", path, rc, elapsed);
                }
            } catch (Exception e) {
                log.warn("Background cleanup of {} errored after {}ms: {}",
                        path, System.currentTimeMillis() - start, e.getMessage());
            } finally {
                pendingWorkspaceDeletes.remove(holder[0]);
            }
        }, "workspace-cleanup");
        holder[0] = t;
        t.setDaemon(true);
        pendingWorkspaceDeletes.add(t);
        t.start();
    }

    /**
     * On startup, sweep the workspace for {@code *.trash-*} directories left
     * behind by a previous pod that was killed mid-cleanup, and delete them on
     * background threads so they don't accumulate and fill the volume.
     */
    private void sweepOrphanedTrashOnStartup() {
        if (workspaceDir == null || workspaceDir.isBlank()) {
            return;
        }
        File ws = new File(workspaceDir);
        if (!ws.isDirectory()) {
            return;
        }
        File[] entries = ws.listFiles((d, name) -> name.contains(".trash-"));
        if (entries == null || entries.length == 0) {
            return;
        }
        log.info("Found {} orphaned trash director{} in {}; deleting in background",
                entries.length, entries.length == 1 ? "y" : "ies", workspaceDir);
        for (File entry : entries) {
            scheduleBackgroundDelete(entry.toPath());
        }
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

        if (shouldRunAsDifferentUser()) {
            return materializeSettingsSshKeyForRunAsUser(normalized);
        }
        return materializeSettingsSshKeyForCurrentUser(normalized);
    }

    /**
     * Materialize the settings SSH key when subprocesses run as the JVM's own
     * user: the JVM can create and read the file directly, so no ownership
     * change is needed.
     */
    private String materializeSettingsSshKeyForCurrentUser(String normalized) {
        try {
            File sshDir = new File(workspaceDir, ".ssh");
            if (!sshDir.exists() && !sshDir.mkdirs()) {
                log.warn("Failed to create directory for settings SSH key: {}", sshDir);
                return null;
            }
            try {
                Files.setPosixFilePermissions(sshDir.toPath(),
                        PosixFilePermissions.fromString("rwx------"));
            } catch (UnsupportedOperationException ignored) {
                // Non-POSIX filesystem; best-effort fall-through
            }
            Path keyFile = new File(sshDir, "settings_git_id").toPath();
            boolean needsWrite = !Files.exists(keyFile) ||
                    !new String(Files.readAllBytes(keyFile), StandardCharsets.UTF_8).equals(normalized);
            if (needsWrite) {
                Files.write(keyFile, normalized.getBytes(StandardCharsets.UTF_8));
                log.info("Materialized settings SSH key at {}", keyFile);
            }
            try {
                Files.setPosixFilePermissions(keyFile,
                        PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException ignored) {
                // Non-POSIX filesystem; best-effort fall-through (key may still work)
            }
            return keyFile.toString();
        } catch (Exception e) {
            log.error("Failed to materialize settings SSH key: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * Materialize the settings SSH key when git/Claude subprocesses run as a
     * different OS user via {@code runuser}. The key is created inside that
     * user's own {@code ~/.ssh}, and both the directory and the file are created
     * <em>as</em> that user, so they are owned by it from the start.
     *
     * <p>The previous approach created the file as root and then {@code chown}-ed
     * it to the run-as user. In hardened containers where {@code CAP_CHOWN} is
     * dropped, that chown fails with EPERM ("Operation not permitted"), leaving a
     * root-owned 0600 key the run-as user cannot read; ssh then fails with only
     * git's generic "Could not read from remote repository." Creating the files
     * as the run-as user sidesteps chown entirely.
     */
    private String materializeSettingsSshKeyForRunAsUser(String normalized) {
        String runAsHome = resolveRunAsUserHome();
        if (runAsHome == null) {
            log.error("Cannot materialize settings SSH key: could not resolve home "
                    + "directory for run-as user '{}'", claudeRunAsUser);
            return null;
        }
        Path sshDir = Path.of(runAsHome, ".ssh");
        Path keyFile = sshDir.resolve("settings_git_id");
        try {
            // The JVM runs as root, so it can read the key file even though it is
            // owned by the run-as user — skip the rewrite when content is unchanged.
            if (Files.exists(keyFile)
                    && new String(Files.readAllBytes(keyFile), StandardCharsets.UTF_8)
                            .equals(normalized)) {
                return keyFile.toString();
            }
            // Create ~/.ssh (0700) as the run-as user so the directory is owned by it.
            if (!runAsRunAsUser(List.of("mkdir", "-p", "-m", "700", sshDir.toString()), null)) {
                log.error("Failed to create {} as run-as user '{}' — settings SSH key "
                        + "cannot be materialized", sshDir, claudeRunAsUser);
                return null;
            }
            // Write the key file as the run-as user; umask 177 + chmod enforce 0600.
            if (!runAsRunAsUser(
                    List.of("sh", "-c", "umask 177 && cat > \"$1\" && chmod 600 \"$1\"",
                            "sh", keyFile.toString()),
                    normalized.getBytes(StandardCharsets.UTF_8))) {
                log.error("Failed to write settings SSH key at {} as run-as user '{}' — "
                        + "git operations using this key will fail", keyFile, claudeRunAsUser);
                return null;
            }
            log.info("Materialized settings SSH key at {} (owned by run-as user '{}')",
                    keyFile, claudeRunAsUser);
            return keyFile.toString();
        } catch (Exception e) {
            log.error("Failed to materialize settings SSH key: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * Run {@code argv} as the configured run-as user via {@code runuser}, feeding
     * {@code stdin} to the process when non-null. Returns true on exit code 0.
     */
    private boolean runAsRunAsUser(List<String> argv, byte[] stdin) {
        try {
            List<String> cmd = new java.util.ArrayList<>();
            cmd.add("runuser");
            cmd.add("-u");
            cmd.add(claudeRunAsUser);
            cmd.add("--");
            cmd.addAll(argv);
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            try (java.io.OutputStream os = p.getOutputStream()) {
                if (stdin != null) {
                    os.write(stdin);
                }
            }
            String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            int exit = p.waitFor();
            if (exit != 0) {
                log.warn("`runuser -u {} -- {}` failed (exit {}): {}",
                        claudeRunAsUser, String.join(" ", argv), exit,
                        output.isEmpty() ? "(no output)" : output);
                return false;
            }
            return true;
        } catch (Exception e) {
            log.warn("Failed to run command as run-as user '{}': {}",
                    claudeRunAsUser, e.getMessage());
            return false;
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
            // UserKnownHostsFile=/dev/null avoids known_hosts writes failing when
            // the run-as-user's ~/.ssh isn't writable; BatchMode=yes makes ssh fail
            // fast with a diagnostic instead of silently hanging on a hidden prompt.
            pb.environment().put("GIT_SSH_COMMAND",
                    "ssh -i " + sshKeyPath
                            + " -o StrictHostKeyChecking=no"
                            + " -o UserKnownHostsFile=/dev/null"
                            + " -o IdentitiesOnly=yes"
                            + " -o BatchMode=yes");
        }
    }

    /**
     * Resolve the SSH key path. Prefers a key configured via the Settings page
     * (stored in the DB, materialized to a 0600 file), then the explicitly
     * configured app.git-ssh-key-path. When git runs as the JVM user, auto-detects
     * from {@code ~/.ssh}; when git runs as a different user via {@code runuser},
     * only considers that user's {@code ~/.ssh} (since keys in the JVM user's
     * {@code ~/.ssh} would not be readable by the runuser target). Finally falls
     * back to the git config {@code core.sshCommand} key if present.
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

        // If running git as a different user, only consider that user's ~/.ssh — keys
        // in root's ~/.ssh aren't readable by an unprivileged user via runuser, and
        // returning such a path would make ssh fail with "Could not read from remote
        // repository." (the underlying cause being a Permission denied on the key file).
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
        } else {
            // Auto-detect from current user's ~/.ssh only when git will run as the
            // same user (no runuser wrapping). Otherwise the key would be unreadable
            // to the runuser target user.
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
     *
     * <p>Uses {@code --force} because the {@code suggestion-<id>} branch is
     * automation-owned and deterministic per suggestion id: if a previous
     * run already pushed this branch and we are retrying after a fresh
     * (shallow, single-branch) clone, the local history will not be a
     * fast-forward of the remote, and a plain push would be rejected with
     * "failed to push some refs". The retry is intended to replace the
     * remote branch with our newly-built version.
     */
    public void pushBranch(String repoDir, String branchName) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(wrapCommandForUser(
                java.util.List.of("git", "push", "-u", "--force", "origin", branchName)));
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
     * Drive the Claude CLI to merge a pull request branch into the repository's
     * default branch using plain git (SSH-based), then push. Used as a fallback
     * when the GitHub REST API merge fails and as the implementation of the
     * admin "Retry Merge" action. Returns true only when the CLI reports
     * {@code "status":"MERGED"} in its JSON response.
     *
     * <p>The prompt explicitly tells Claude the SSH key path so the merge
     * subprocess uses the right credentials for git push, even if it spawns
     * helper processes that don't inherit our GIT_SSH_COMMAND env var.
     */
    public boolean mergePullRequestViaCli(String repoUrl, int prNumber,
                                          String branchName, String workingDir) {
        if (workingDir == null || workingDir.isBlank()) {
            log.warn("mergePullRequestViaCli: no working directory for PR #{} — cannot merge", prNumber);
            return false;
        }
        String sshKey = resolveGitSshKeyPath();
        String sshKeyText = (sshKey != null && !sshKey.isBlank())
                ? sshKey
                : "(no SSH key configured — git will fall back to the default ssh agent / ~/.ssh config)";
        String sshCommandText = (sshKey != null && !sshKey.isBlank())
                ? "ssh -i " + sshKey + " -o StrictHostKeyChecking=no -o BatchMode=yes -o IdentitiesOnly=yes"
                : "ssh -o StrictHostKeyChecking=no -o BatchMode=yes";

        String prompt = String.format(
                "Merge the pull request branch '%s' into the default branch of the repository " +
                "at %s and push the merge commit. Use plain git over SSH — no GitHub API token " +
                "is needed; the SSH key listed below provides the auth for git push.\n\n" +
                "Working directory: %s\n" +
                "Pull request: #%d\n" +
                "Branch to merge: %s\n" +
                "SSH key for git remote operations: %s\n\n" +
                "Steps to perform (run them yourself, do not just describe them):\n" +
                "1. cd into the working directory above.\n" +
                "2. Make every git remote operation use the SSH key above:\n" +
                "     export GIT_SSH_COMMAND=\"%s\"\n" +
                "   (the harness already sets this for the top-level process; re-exporting it " +
                "guarantees any helper you spawn inherits it).\n" +
                "3. git fetch origin --no-tags --depth=1 '%s' && git fetch origin --no-tags\n" +
                "4. Detect the default branch and check it out:\n" +
                "     default_branch=$(git symbolic-ref refs/remotes/origin/HEAD 2>/dev/null | " +
                "sed 's@^refs/remotes/origin/@@')\n" +
                "     [ -z \"$default_branch\" ] && default_branch=main\n" +
                "     git checkout \"$default_branch\"\n" +
                "     git pull --ff-only origin \"$default_branch\"\n" +
                "5. Merge the suggestion branch with a real merge commit:\n" +
                "     git merge --no-ff origin/%s -m \"Merge pull request #%d from %s\"\n" +
                "6. CONFLICT RESOLUTION (only if git merge reports conflicts):\n" +
                "   Resolve every conflict by KEEPING THE FUNCTIONALITY OF BOTH SIDES — the\n" +
                "   default branch's changes AND the suggestion branch's changes must both\n" +
                "   continue to work after the resolution. Do not just pick one side.\n" +
                "   For each conflicted file:\n" +
                "     a. Read the conflicted hunks (markers <<<<<<<, =======, >>>>>>>) plus\n" +
                "        enough surrounding context to understand the intent on each side.\n" +
                "     b. Look at the commit history of both sides for that file\n" +
                "        (git log -p HEAD..origin/%s -- <file> and\n" +
                "         git log -p origin/<default_branch>..HEAD -- <file>) so you know\n" +
                "        what each branch was trying to accomplish.\n" +
                "     c. Edit the file to merge BOTH behaviours — combine the new code, keep\n" +
                "        all added fields/methods/branches, reconcile renamed identifiers,\n" +
                "        merge import lists, and unify any config so neither side's feature\n" +
                "        is silently dropped. Remove every conflict marker.\n" +
                "     d. git add <file>\n" +
                "   After every conflict is resolved:\n" +
                "     a. Run the project's test command (e.g. ./gradlew test, npm test) if\n" +
                "        one is configured. If tests fail solely because of your conflict\n" +
                "        resolution, fix the resolution and re-run.\n" +
                "     b. git commit --no-edit  (uses the merge commit message git already\n" +
                "        prepared at step 5)\n" +
                "   Only fall back to {\"status\":\"CONFLICT\"} if the two sides are genuinely\n" +
                "   semantically incompatible (e.g. they both changed the same business\n" +
                "   logic in irreconcilable ways) and no honest merge of the behaviours\n" +
                "   exists — never just because there are conflict markers.\n" +
                "7. Push:\n" +
                "     git push origin \"$default_branch\"\n\n" +
                "Output ONE final JSON line summarising the outcome.\n" +
                "On success:\n" +
                "  {\"status\": \"MERGED\", \"message\": \"merged %s into <default_branch> and pushed (mention any conflicts you resolved and how)\"}\n" +
                "Only if the two sides are genuinely impossible to reconcile after a real attempt:\n" +
                "  {\"status\": \"CONFLICT\", \"message\": \"which paths conflict, what each side was doing, and why both behaviours cannot coexist\"}\n" +
                "On any other failure (push rejected, branch missing, auth error, etc.):\n" +
                "  {\"status\": \"FAILED\", \"message\": \"what went wrong\"}\n",
                branchName, repoUrl, workingDir, prNumber, branchName,
                sshKeyText, sshCommandText, branchName, branchName, prNumber, branchName,
                branchName, branchName);

        String sessionId = generateSessionId();
        try {
            String response = sendToClaude(prompt, sessionId, workingDir, null, null,
                    "merge-pr-" + prNumber, resolveModel(), 0);
            String json = extractJsonObject(response);
            if (json != null) {
                JsonNode root = objectMapper.readTree(json);
                String status = root.has("status") ? root.get("status").asText() : "";
                String message = root.has("message") ? root.get("message").asText() : "";
                if ("MERGED".equalsIgnoreCase(status)) {
                    log.info("CLI merged PR #{}: {}", prNumber, message);
                    return true;
                }
                log.warn("CLI did not merge PR #{}: status={} message={}", prNumber, status, message);
                return false;
            }
            // No structured response — be conservative and treat as failure.
            log.warn("CLI merge of PR #{} produced no structured response", prNumber);
            return false;
        } catch (Exception e) {
            log.warn("CLI merge of PR #{} failed: {}", prNumber, e.getMessage());
            return false;
        }
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
     *   <li>{@code is_error} JSON flag combined with authentication, not-logged-in, or model-not-found messages</li>
     *   <li>{@code is_error} JSON flag with {@code subtype":"error_max_turns"} — the CLI hit the
     *       {@code --max-turns} cap, which is deterministic and will recur on every retry with the
     *       same prompt and turn budget; retrying just wastes the rate-limit window</li>
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

        // PERMANENT: is_error with authentication / not-logged-in / model-not-found
        // messages. Retrying these is pointless — they need a config or login fix.
        if (rawOutput != null && rawOutput.contains("is_error")) {
            String lower = rawOutput.toLowerCase();
            if (lower.contains("authentication") || lower.contains("unauthorized") ||
                    lower.contains("invalid api key") || lower.contains("model not found") ||
                    lower.contains("model_not_found") || lower.contains("invalid_api_key") ||
                    lower.contains("not logged in") || lower.contains("please run /login")) {
                return ClaudeFailureType.PERMANENT;
            }
            // PERMANENT: the CLI hit the --max-turns cap. The prompt and turn budget
            // are identical on retry, so it will hit the same cap every time —
            // retrying just burns the rate-limit window and delays the surfaced
            // failure. Classify it as PERMANENT so the caller fails fast.
            if (lower.contains("error_max_turns") || lower.contains("\"subtype\":\"error_max_turns\"")) {
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
