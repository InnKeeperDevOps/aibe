package com.sitemanager.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.sitemanager.model.enums.CostResetPeriod;
import jakarta.persistence.*;

import java.math.BigDecimal;

@Entity
@Table(name = "site_settings")
public class SiteSettings {

    public static final String DEFAULT_CLAUDE_CONFIG =
            "{\"theme\":\"light\",\"hasCompletedOnboarding\":true,"
            + "\"bypassPermissionsModeAccepted\":true,"
            + "\"projects\":{\"/workspace\":{\"hasTrustDialogAccepted\":true,\"hasCompletedProjectOnboarding\":true}}}";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private boolean allowAnonymousSuggestions = true;

    @Column(nullable = false)
    private boolean allowVoting = true;

    @Column
    private String targetRepoUrl;

    @Column(nullable = false)
    private int suggestionTimeoutMinutes = 1440;

    @Column(nullable = false)
    private boolean requireApproval = true;

    @Column
    private String siteName = "Site Suggestion Platform";

    @Column
    private String githubToken;

    @Column
    private String claudeModel;

    @Column
    private String claudeModelExpert;

    @Column
    private Integer claudeMaxTurnsExpert;

    @Column
    private String slackWebhookUrl;

    @Column(name = "max_concurrent_suggestions")
    private Integer maxConcurrentSuggestions = 1;

    @Column(name = "auto_merge_pr", nullable = false)
    private boolean autoMergePr = false;

    @Column(columnDefinition = "BOOLEAN DEFAULT 0")
    private boolean requireRegistrationApproval = false;

    @Column(name = "registrations_enabled", nullable = false)
    private boolean registrationsEnabled = true;

    @Column(name = "git_ssh_key", columnDefinition = "TEXT")
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String gitSshKey;

    @Column(name = "git_ssh_public_key", columnDefinition = "TEXT")
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private String gitSshPublicKey;

    @Column(name = "claude_credentials", columnDefinition = "TEXT")
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String claudeCredentials;

    @Column(name = "claude_config", columnDefinition = "TEXT")
    private String claudeConfig = DEFAULT_CLAUDE_CONFIG;

    /**
     * Newline-separated list of folders the AI is allowed to read/modify in
     * the target repository, relative to the repo root. NULL or blank means
     * no restriction — AI works on the whole repo (historic behaviour).
     */
    @Column(name = "managed_folders", columnDefinition = "TEXT")
    private String managedFolders;

    /**
     * Maximum dollar spend allowed for any single suggestion. {@code null}
     * means no cap is enforced. Stored as a fixed-precision decimal so
     * sub-cent costs are not lost.
     */
    @Column(name = "max_cost_per_suggestion_usd", precision = 19, scale = 6)
    private BigDecimal maxCostPerSuggestionUsd;

    /**
     * Maximum dollar spend allowed across the whole system for the current
     * reset window. {@code null} means no cap is enforced.
     */
    @Column(name = "max_total_cost_usd", precision = 19, scale = 6)
    private BigDecimal maxTotalCostUsd;

    /**
     * Window that the global spend total is measured against. Defaults to
     * NEVER (lifetime running total).
     */
    @Column(name = "global_cost_reset_period", length = 20, nullable = false)
    @Enumerated(EnumType.STRING)
    private CostResetPeriod globalCostResetPeriod = CostResetPeriod.NEVER;

    /**
     * Whether proactive spending alerts are sent to admins as spend crosses
     * warning thresholds and again when a cap is reached. Defaults to
     * enabled — admins can opt out by clearing this flag.
     */
    @Column(name = "spending_alerts_enabled", nullable = false)
    private boolean spendingAlertsEnabled = true;

    /**
     * Comma-separated percent values (1-99) that trigger warning alerts as
     * the relevant spend approaches its cap. For example, "75,90" means
     * admins receive a warning the first time spend within a window crosses
     * 75% of the cap, and again at 90%. A separate "cap reached" alert is
     * always sent at 100% regardless of this list.
     */
    @Column(name = "spending_alert_thresholds", length = 255)
    private String spendingAlertThresholds = "75,90";

    /**
     * Newline- or comma-separated list of recipient identifiers (admin
     * usernames or email addresses) that the alert system addresses. The
     * value is configured server-side by admins; it is never derived from
     * request input, so an attacker cannot redirect alerts by sending a
     * crafted body.
     */
    @Column(name = "spending_alert_recipients", columnDefinition = "TEXT")
    private String spendingAlertRecipients;

    public SiteSettings() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public boolean isAllowAnonymousSuggestions() { return allowAnonymousSuggestions; }
    public void setAllowAnonymousSuggestions(boolean v) { this.allowAnonymousSuggestions = v; }
    public boolean isAllowVoting() { return allowVoting; }
    public void setAllowVoting(boolean v) { this.allowVoting = v; }
    public String getTargetRepoUrl() { return targetRepoUrl; }
    public void setTargetRepoUrl(String v) { this.targetRepoUrl = v; }
    public int getSuggestionTimeoutMinutes() { return suggestionTimeoutMinutes; }
    public void setSuggestionTimeoutMinutes(int v) { this.suggestionTimeoutMinutes = v; }
    public boolean isRequireApproval() { return requireApproval; }
    public void setRequireApproval(boolean v) { this.requireApproval = v; }
    public String getSiteName() { return siteName; }
    public void setSiteName(String v) { this.siteName = v; }
    public String getGithubToken() { return githubToken; }
    public void setGithubToken(String v) { this.githubToken = v; }
    public String getClaudeModel() { return claudeModel; }
    public void setClaudeModel(String v) { this.claudeModel = v; }
    public String getClaudeModelExpert() { return claudeModelExpert; }
    public void setClaudeModelExpert(String v) { this.claudeModelExpert = v; }
    public Integer getClaudeMaxTurnsExpert() { return claudeMaxTurnsExpert; }
    public void setClaudeMaxTurnsExpert(Integer v) { this.claudeMaxTurnsExpert = v; }
    public String getSlackWebhookUrl() { return slackWebhookUrl; }
    public void setSlackWebhookUrl(String v) { this.slackWebhookUrl = v; }
    public Integer getMaxConcurrentSuggestions() { return maxConcurrentSuggestions; }
    public void setMaxConcurrentSuggestions(Integer v) { this.maxConcurrentSuggestions = v; }
    public boolean isAutoMergePr() { return autoMergePr; }
    public void setAutoMergePr(boolean v) { this.autoMergePr = v; }
    public boolean isRequireRegistrationApproval() { return requireRegistrationApproval; }
    public void setRequireRegistrationApproval(boolean v) { this.requireRegistrationApproval = v; }
    public boolean isRegistrationsEnabled() { return registrationsEnabled; }
    public void setRegistrationsEnabled(boolean v) { this.registrationsEnabled = v; }
    public String getGitSshKey() { return gitSshKey; }
    public void setGitSshKey(String v) { this.gitSshKey = v; }
    public String getGitSshPublicKey() { return gitSshPublicKey; }
    public void setGitSshPublicKey(String v) { this.gitSshPublicKey = v; }
    public String getClaudeCredentials() { return claudeCredentials; }
    public void setClaudeCredentials(String v) { this.claudeCredentials = v; }
    public String getManagedFolders() { return managedFolders; }
    public void setManagedFolders(String v) { this.managedFolders = v; }
    public String getClaudeConfig() { return claudeConfig; }
    public void setClaudeConfig(String v) { this.claudeConfig = v; }
    public BigDecimal getMaxCostPerSuggestionUsd() { return maxCostPerSuggestionUsd; }
    public void setMaxCostPerSuggestionUsd(BigDecimal v) { this.maxCostPerSuggestionUsd = v; }
    public BigDecimal getMaxTotalCostUsd() { return maxTotalCostUsd; }
    public void setMaxTotalCostUsd(BigDecimal v) { this.maxTotalCostUsd = v; }
    public CostResetPeriod getGlobalCostResetPeriod() { return globalCostResetPeriod; }
    public void setGlobalCostResetPeriod(CostResetPeriod v) {
        this.globalCostResetPeriod = v != null ? v : CostResetPeriod.NEVER;
    }
    public boolean isSpendingAlertsEnabled() { return spendingAlertsEnabled; }
    public void setSpendingAlertsEnabled(boolean v) { this.spendingAlertsEnabled = v; }
    public String getSpendingAlertThresholds() { return spendingAlertThresholds; }
    public void setSpendingAlertThresholds(String v) { this.spendingAlertThresholds = v; }
    public String getSpendingAlertRecipients() { return spendingAlertRecipients; }
    public void setSpendingAlertRecipients(String v) { this.spendingAlertRecipients = v; }

    @JsonProperty("hasGitSshKey")
    public boolean hasGitSshKey() {
        return gitSshKey != null && !gitSshKey.isBlank();
    }

    @JsonProperty("hasClaudeCredentials")
    public boolean hasClaudeCredentials() {
        return claudeCredentials != null && !claudeCredentials.isBlank();
    }
}
