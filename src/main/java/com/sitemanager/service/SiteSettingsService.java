package com.sitemanager.service;

import com.sitemanager.model.SiteSettings;
import com.sitemanager.model.enums.CostResetPeriod;
import com.sitemanager.repository.SiteSettingsRepository;
import com.sitemanager.repository.SpendingAlertStateRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Objects;

@Service
public class SiteSettingsService {

    private static final Logger log = LoggerFactory.getLogger(SiteSettingsService.class);

    private final SiteSettingsRepository settingsRepository;
    private final ClaudeService claudeService;
    private final ApplicationEventPublisher eventPublisher;
    private final SpendingAlertStateRepository alertStateRepository;

    public SiteSettingsService(SiteSettingsRepository settingsRepository,
                               ClaudeService claudeService,
                               ApplicationEventPublisher eventPublisher,
                               SpendingAlertStateRepository alertStateRepository) {
        this.settingsRepository = settingsRepository;
        this.claudeService = claudeService;
        this.eventPublisher = eventPublisher;
        this.alertStateRepository = alertStateRepository;
    }

    public SiteSettings getSettings() {
        return settingsRepository.findAll().stream()
                .findFirst()
                .orElseGet(() -> settingsRepository.save(new SiteSettings()));
    }

    public SiteSettings generateSshKeyPair() {
        SshKeyGenerator.GeneratedKey generated;
        try {
            generated = SshKeyGenerator.generate("aibe@" + java.time.Instant.now().toString());
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate SSH key: " + e.getMessage(), e);
        }
        SiteSettings current = getSettings();
        current.setGitSshKey(generated.privateKeyPem);
        current.setGitSshPublicKey(generated.publicKeyAuthorized);
        return settingsRepository.save(current);
    }

    public SiteSettings updateSettings(SiteSettings updated) {
        validateSpendLimits(updated);
        SiteSettings current = getSettings();
        BigDecimal previousPerSuggestionLimit = current.getMaxCostPerSuggestionUsd();
        BigDecimal previousTotalLimit = current.getMaxTotalCostUsd();
        CostResetPeriod previousResetPeriod = current.getGlobalCostResetPeriod();
        String previousThresholds = current.getSpendingAlertThresholds();
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
        current.setManagedFolders(updated.getManagedFolders());
        current.setMaxCostPerSuggestionUsd(updated.getMaxCostPerSuggestionUsd());
        current.setMaxTotalCostUsd(updated.getMaxTotalCostUsd());
        current.setGlobalCostResetPeriod(
                updated.getGlobalCostResetPeriod() != null
                        ? updated.getGlobalCostResetPeriod()
                        : CostResetPeriod.NEVER);
        current.setSpendingAlertsEnabled(updated.isSpendingAlertsEnabled());
        current.setSpendingAlertThresholds(updated.getSpendingAlertThresholds());
        current.setSpendingAlertRecipients(updated.getSpendingAlertRecipients());
        // SSH key: only update when caller provides a non-null value, so blank submissions
        // from the UI (where the existing key is never echoed back) preserve the stored key.
        // An explicit empty string clears the key.
        if (updated.getGitSshKey() != null) {
            current.setGitSshKey(updated.getGitSshKey().isBlank() ? null : updated.getGitSshKey());
            // A pasted key replaces any previously-generated pair, so the stored public key
            // no longer matches. Clear it so the UI doesn't surface a stale value; users who
            // want a paired public key should use POST /git-ssh-key/generate.
            current.setGitSshPublicKey(null);
        }
        // Claude credentials are managed exclusively via /api/claude-cli-login; never
        // clobber them from a generic settings update.
        SiteSettings saved = settingsRepository.save(current);

        reArmSpendingAlertsIfNeeded(
                previousPerSuggestionLimit, saved.getMaxCostPerSuggestionUsd(),
                previousTotalLimit, saved.getMaxTotalCostUsd(),
                previousResetPeriod, saved.getGlobalCostResetPeriod(),
                previousThresholds, saved.getSpendingAlertThresholds());

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

    private void validateSpendLimits(SiteSettings updated) {
        if (updated.getMaxCostPerSuggestionUsd() != null
                && updated.getMaxCostPerSuggestionUsd().signum() < 0) {
            throw new IllegalArgumentException("Per-suggestion spending limit cannot be negative");
        }
        if (updated.getMaxTotalCostUsd() != null
                && updated.getMaxTotalCostUsd().signum() < 0) {
            throw new IllegalArgumentException("Total spending limit cannot be negative");
        }
    }

    /**
     * When the configured limits or alert thresholds change, drop the
     * recorded alert-fired rows so the next cost recording can deliver
     * fresh warnings under the new ceiling.
     */
    private void reArmSpendingAlertsIfNeeded(BigDecimal previousPerSuggestionLimit,
                                             BigDecimal newPerSuggestionLimit,
                                             BigDecimal previousTotalLimit,
                                             BigDecimal newTotalLimit,
                                             CostResetPeriod previousResetPeriod,
                                             CostResetPeriod newResetPeriod,
                                             String previousThresholds,
                                             String newThresholds) {
        try {
            boolean perSuggestionChanged =
                    !valuesEqual(previousPerSuggestionLimit, newPerSuggestionLimit)
                    || !Objects.equals(previousThresholds, newThresholds);
            boolean globalChanged =
                    !valuesEqual(previousTotalLimit, newTotalLimit)
                    || !Objects.equals(previousResetPeriod, newResetPeriod)
                    || !Objects.equals(previousThresholds, newThresholds);

            if (globalChanged) {
                alertStateRepository.deleteAllGlobalAlerts();
            }
            if (perSuggestionChanged) {
                // Per-suggestion alerts target individual suggestions; the
                // simplest correct re-arming is to drop every per-suggestion
                // row so any suggestion with refreshed headroom can fire
                // fresh warnings under the new cap.
                alertStateRepository.deleteAllPerSuggestionAlerts();
            }
        } catch (Exception e) {
            log.warn("Failed to re-arm spending alerts after settings change: {}", e.getMessage());
        }
    }

    private static boolean valuesEqual(BigDecimal a, BigDecimal b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        return a.compareTo(b) == 0;
    }
}
