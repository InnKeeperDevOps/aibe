package com.sitemanager.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * One Claude CLI invocation: the exact command and prompt sent to the CLI and
 * the raw output it returned. Persisted for every CLI call so admins can audit
 * what was asked and what came back. See the admin "Claude Logs" page.
 */
@Entity
@Table(name = "claude_cli_logs")
public class ClaudeCliLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Matches the [CLAUDE-REQ-N] counter in the application logs. */
    private Long requestId;

    /** e.g. "execute-task-1", "expert-review:...", "evaluate". */
    private String operationType;

    private String model;

    @Column(length = 1000)
    private String workingDir;

    /** Full CLI command line, with the prompt argument masked for readability. */
    @Column(columnDefinition = "TEXT")
    private String command;

    /** The prompt sent to the CLI via {@code -p}. */
    @Column(columnDefinition = "TEXT")
    private String prompt;

    /** Everything the CLI wrote to stdout/stderr. */
    @Column(columnDefinition = "TEXT")
    private String rawOutput;

    /** Process exit code; -1 when the call timed out before exiting. */
    private Integer exitCode;

    private Long durationMs;

    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getRequestId() { return requestId; }
    public void setRequestId(Long requestId) { this.requestId = requestId; }

    public String getOperationType() { return operationType; }
    public void setOperationType(String operationType) { this.operationType = operationType; }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public String getWorkingDir() { return workingDir; }
    public void setWorkingDir(String workingDir) { this.workingDir = workingDir; }

    public String getCommand() { return command; }
    public void setCommand(String command) { this.command = command; }

    public String getPrompt() { return prompt; }
    public void setPrompt(String prompt) { this.prompt = prompt; }

    public String getRawOutput() { return rawOutput; }
    public void setRawOutput(String rawOutput) { this.rawOutput = rawOutput; }

    public Integer getExitCode() { return exitCode; }
    public void setExitCode(Integer exitCode) { this.exitCode = exitCode; }

    public Long getDurationMs() { return durationMs; }
    public void setDurationMs(Long durationMs) { this.durationMs = durationMs; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
