package com.sitemanager.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;

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
    public String getClaudeConfig() { return claudeConfig; }
    public void setClaudeConfig(String v) { this.claudeConfig = v; }

    @JsonProperty("hasGitSshKey")
    public boolean hasGitSshKey() {
        return gitSshKey != null && !gitSshKey.isBlank();
    }

    @JsonProperty("hasClaudeCredentials")
    public boolean hasClaudeCredentials() {
        return claudeCredentials != null && !claudeCredentials.isBlank();
    }
}
