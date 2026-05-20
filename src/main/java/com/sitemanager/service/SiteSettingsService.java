package com.sitemanager.service;

import com.sitemanager.model.SiteSettings;
import com.sitemanager.repository.SiteSettingsRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

@Service
public class SiteSettingsService {

    private static final Logger log = LoggerFactory.getLogger(SiteSettingsService.class);

    private final SiteSettingsRepository settingsRepository;
    private final ClaudeService claudeService;
    private final ApplicationEventPublisher eventPublisher;

    public SiteSettingsService(SiteSettingsRepository settingsRepository,
                               ClaudeService claudeService,
                               ApplicationEventPublisher eventPublisher) {
        this.settingsRepository = settingsRepository;
        this.claudeService = claudeService;
        this.eventPublisher = eventPublisher;
    }

    public SiteSettings getSettings() {
        return settingsRepository.findAll().stream()
                .findFirst()
                .orElseGet(() -> settingsRepository.save(new SiteSettings()));
    }

    public SiteSettings updateSettings(SiteSettings updated) {
        SiteSettings current = getSettings();
        current.setAllowAnonymousSuggestions(updated.isAllowAnonymousSuggestions());
        current.setAllowVoting(updated.isAllowVoting());
        current.setTargetRepoUrl(updated.getTargetRepoUrl());
        current.setSuggestionTimeoutMinutes(updated.getSuggestionTimeoutMinutes());
        current.setRequireApproval(updated.isRequireApproval());
        current.setSiteName(updated.getSiteName());
        current.setGithubToken(updated.getGithubToken());
        current.setClaudeModel(updated.getClaudeModel());
        current.setClaudeModelExpert(updated.getClaudeModelExpert());
        current.setClaudeMaxTurnsExpert(updated.getClaudeMaxTurnsExpert());
        current.setSlackWebhookUrl(updated.getSlackWebhookUrl());
        current.setMaxConcurrentSuggestions(updated.getMaxConcurrentSuggestions());
        current.setAutoMergePr(updated.isAutoMergePr());
        current.setRequireRegistrationApproval(updated.isRequireRegistrationApproval());
        current.setRegistrationsEnabled(updated.isRegistrationsEnabled());
        // SSH key: only update when caller provides a non-null value, so blank submissions
        // from the UI (where the existing key is never echoed back) preserve the stored key.
        // An explicit empty string clears the key.
        if (updated.getGitSshKey() != null) {
            current.setGitSshKey(updated.getGitSshKey().isBlank() ? null : updated.getGitSshKey());
        }
        SiteSettings saved = settingsRepository.save(current);

        // Re-clone the target repository into main-repo/ so files are up to date
        String repoUrl = saved.getTargetRepoUrl();
        if (repoUrl != null && !repoUrl.isBlank()) {
            try {
                log.info("Settings updated, re-cloning repository: {}", repoUrl);
                claudeService.cloneMainRepository(repoUrl);

                // Notify listeners that main-repo has been refreshed
                eventPublisher.publishEvent(new MainRepoUpdatedEvent(this, repoUrl));
            } catch (Exception e) {
                log.error("Failed to re-clone repository after settings update: {}", e.getMessage(), e);
            }
        }

        return saved;
    }
}
