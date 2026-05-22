package com.sitemanager.service;

import com.sitemanager.model.SiteSettings;
import com.sitemanager.repository.SiteSettingsRepository;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Drives an interactive {@code claude login} session so the OAuth URL and
 * the resulting code can be relayed through the web UI. The CLI is wrapped
 * in {@code script} to give it a pseudo-tty (the CLI refuses to accept input
 * when stdin is not a terminal). On a successful login the resulting
 * {@code ~/.claude/.credentials.json} is read off disk and persisted on
 * {@link SiteSettings} so it survives container restarts.
 */
@Service
public class ClaudeCliLoginService {

    private static final Logger log = LoggerFactory.getLogger(ClaudeCliLoginService.class);
    private static final Pattern URL_RE = Pattern.compile("https?://[\\w./?=&#%:+_\\-]+");
    private static final Pattern ANSI_RE = Pattern.compile("\\x1b\\[[0-9;?]*[A-Za-z]|\\x1b\\][^\\x07]*\\x07");
    private static final int MAX_LOG_LINES = 200;
    private static final long IDLE_TIMEOUT_MS = 10 * 60 * 1000L; // 10 minutes

    public enum Status { IDLE, STARTING, AWAITING_CODE, SUBMITTING, SUCCESS, FAILED }

    private final ClaudeService claudeService;
    private final SiteSettingsRepository settingsRepository;
    private final String scriptBinary;
    private final String loginCommand;

    private final Object lock = new Object();
    private Process process;
    private Thread readerThread;
    private OutputStream processStdin;
    private Status status = Status.IDLE;
    private String url;
    private String errorMessage;
    private long startedAt;
    private final ConcurrentLinkedDeque<String> logLines = new ConcurrentLinkedDeque<>();
    private final StringBuilder outputBuffer = new StringBuilder();

    public ClaudeCliLoginService(ClaudeService claudeService,
                                 SiteSettingsRepository settingsRepository,
                                 @Value("${app.claude-login-script-binary:script}") String scriptBinary,
                                 @Value("${app.claude-login-command:setup-token}") String loginCommand) {
        this.claudeService = claudeService;
        this.settingsRepository = settingsRepository;
        this.scriptBinary = scriptBinary;
        this.loginCommand = loginCommand;
    }

    public Map<String, Object> getStatusSnapshot() {
        synchronized (lock) {
            maybeExpireIdle();
            Map<String, Object> snap = new java.util.LinkedHashMap<>();
            snap.put("status", status.name());
            snap.put("url", url);
            snap.put("error", errorMessage);
            snap.put("log", new ArrayList<>(logLines));
            snap.put("hasStoredCredentials", hasStoredCredentials());
            return snap;
        }
    }

    public Map<String, Object> start() {
        synchronized (lock) {
            if (status == Status.STARTING || status == Status.AWAITING_CODE || status == Status.SUBMITTING) {
                return getStatusSnapshot();
            }
            resetState();
            status = Status.STARTING;
            startedAt = System.currentTimeMillis();

            String claudeCli = "claude";
            // `script -qfec "claude <loginCommand>" /dev/null` allocates a PTY so the
            // CLI sees a real terminal on stdin/stdout. -f flushes after each write,
            // -e propagates the wrapped command's exit code, -q is quiet.
            // Defaults to `setup-token`, which mints a long-lived, non-rotating token.
            // `auth login` also works but yields an OAuth refresh token that rotates
            // on every use, so a copy persisted to the DB goes stale and 401s after a
            // pod restart restores it. Override via app.claude-login-command.
            List<String> cmd = new ArrayList<>();
            cmd.add(scriptBinary);
            cmd.add("-qfec");
            cmd.add(claudeCli + " " + loginCommand);
            cmd.add("/dev/null");

            String runAsUser = claudeService.getClaudeRunAsUser();
            List<String> finalCmd;
            if (runAsUser != null && !runAsUser.isBlank()) {
                finalCmd = new ArrayList<>();
                finalCmd.add("runuser");
                finalCmd.add("-u");
                finalCmd.add(runAsUser);
                finalCmd.add("--");
                finalCmd.addAll(cmd);
            } else {
                finalCmd = cmd;
            }

            try {
                ProcessBuilder pb = new ProcessBuilder(finalCmd);
                pb.environment().put("HOME", claudeService.getClaudeCliHome());
                pb.environment().put("TERM", "dumb");
                pb.environment().put("NO_COLOR", "1");
                pb.redirectErrorStream(true);
                process = pb.start();
                processStdin = process.getOutputStream();
                appendLog("$ " + String.join(" ", finalCmd));
                startReader();
            } catch (IOException e) {
                status = Status.FAILED;
                errorMessage = "Failed to start `claude login`: " + e.getMessage();
                log.error("ClaudeCliLogin start failed", e);
            }
            return getStatusSnapshot();
        }
    }

    public Map<String, Object> submitCode(String code) {
        synchronized (lock) {
            if (status != Status.AWAITING_CODE) {
                return getStatusSnapshot();
            }
            if (code == null || code.isBlank()) {
                errorMessage = "Code is required";
                return getStatusSnapshot();
            }
            if (processStdin == null || process == null || !process.isAlive()) {
                status = Status.FAILED;
                errorMessage = "Login session is no longer active";
                return getStatusSnapshot();
            }
            try {
                processStdin.write((code.trim() + "\n").getBytes(StandardCharsets.UTF_8));
                processStdin.flush();
                status = Status.SUBMITTING;
                appendLog("> [code submitted]");
            } catch (IOException e) {
                status = Status.FAILED;
                errorMessage = "Failed to send code: " + e.getMessage();
                log.error("ClaudeCliLogin submitCode failed", e);
            }
            return getStatusSnapshot();
        }
    }

    public Map<String, Object> cancel() {
        synchronized (lock) {
            killProcess();
            if (status != Status.SUCCESS) {
                status = Status.IDLE;
            }
            return getStatusSnapshot();
        }
    }

    public boolean hasStoredCredentials() {
        return settingsRepository.findAll().stream()
                .findFirst()
                .map(SiteSettings::hasClaudeCredentials)
                .orElse(false);
    }

    public Map<String, Object> clearStoredCredentials() {
        SiteSettings settings = settingsRepository.findAll().stream().findFirst().orElse(null);
        if (settings != null) {
            settings.setClaudeCredentials(null);
            settingsRepository.save(settings);
        }
        try {
            Path p = Path.of(claudeService.getClaudeCliHome(), ".claude", ".credentials.json");
            Files.deleteIfExists(p);
        } catch (IOException e) {
            log.debug("Failed to delete credentials file: {}", e.getMessage());
        }
        return getStatusSnapshot();
    }

    @PreDestroy
    public void shutdown() {
        synchronized (lock) {
            killProcess();
        }
    }

    // ── internals ───────────────────────────────────────────────────

    private void resetState() {
        url = null;
        errorMessage = null;
        outputBuffer.setLength(0);
        logLines.clear();
        killProcess();
    }

    private void killProcess() {
        if (process != null && process.isAlive()) {
            process.destroy();
            try {
                if (!process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        process = null;
        processStdin = null;
    }

    private void maybeExpireIdle() {
        if ((status == Status.STARTING || status == Status.AWAITING_CODE || status == Status.SUBMITTING)
                && System.currentTimeMillis() - startedAt > IDLE_TIMEOUT_MS) {
            killProcess();
            status = Status.FAILED;
            errorMessage = "Login session timed out";
        }
    }

    private void startReader() {
        readerThread = new Thread(this::readLoop, "claude-login-reader");
        readerThread.setDaemon(true);
        readerThread.start();
    }

    private void readLoop() {
        Process p = process;
        if (p == null) return;
        byte[] buf = new byte[4096];
        try (var in = p.getInputStream()) {
            int n;
            while ((n = in.read(buf)) >= 0) {
                if (n == 0) continue;
                String chunk = new String(buf, 0, n, StandardCharsets.UTF_8);
                handleChunk(chunk);
            }
        } catch (IOException e) {
            log.debug("claude login reader IO: {}", e.getMessage());
        } finally {
            try {
                int rc = p.waitFor();
                onProcessExit(rc);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void handleChunk(String chunk) {
        String clean = ANSI_RE.matcher(chunk).replaceAll("");
        synchronized (lock) {
            outputBuffer.append(clean);
            for (String line : clean.split("\\r?\\n")) {
                if (!line.isBlank()) {
                    appendLog(line.stripTrailing());
                }
            }
            if (url == null) {
                Matcher m = URL_RE.matcher(outputBuffer);
                if (m.find()) {
                    url = m.group().replaceAll("[.,)\\]'\"]+$", "");
                    if (status == Status.STARTING) {
                        status = Status.AWAITING_CODE;
                    }
                }
            }
        }
    }

    private void onProcessExit(int rc) {
        synchronized (lock) {
            appendLog("[process exited with code " + rc + "]");
            if (rc == 0) {
                try {
                    saveCredentialsFromDisk();
                    status = Status.SUCCESS;
                    errorMessage = null;
                } catch (Exception e) {
                    status = Status.FAILED;
                    errorMessage = "Login finished but failed to read credentials: " + e.getMessage();
                    log.error("Failed to capture credentials after login", e);
                }
            } else {
                status = Status.FAILED;
                if (errorMessage == null) {
                    errorMessage = "`claude login` exited with code " + rc;
                }
            }
            process = null;
            processStdin = null;
        }
    }

    private void saveCredentialsFromDisk() throws IOException {
        Path credsFile = Path.of(claudeService.getClaudeCliHome(), ".claude", ".credentials.json");
        if (!Files.exists(credsFile)) {
            throw new IOException("credentials file not found at " + credsFile);
        }
        String json = Files.readString(credsFile, StandardCharsets.UTF_8);
        if (json.isBlank()) {
            throw new IOException("credentials file is empty");
        }
        SiteSettings settings = settingsRepository.findAll().stream()
                .findFirst()
                .orElseGet(() -> settingsRepository.save(new SiteSettings()));
        settings.setClaudeCredentials(json);
        settingsRepository.save(settings);
        log.info("Saved Claude CLI credentials to site_settings ({} bytes)", json.length());
    }

    private void appendLog(String line) {
        logLines.addLast(line);
        while (logLines.size() > MAX_LOG_LINES) {
            logLines.pollFirst();
        }
    }
}
