package com.sitemanager.service;

import com.sitemanager.model.SiteSettings;
import com.sitemanager.model.enums.CostResetPeriod;
import com.sitemanager.repository.SiteSettingsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class SiteSettingsServiceTest {

    @Autowired
    private SiteSettingsService settingsService;

    @Autowired
    private SiteSettingsRepository settingsRepository;

    @BeforeEach
    void setUp() {
        settingsRepository.deleteAll();
    }

    @Test
    void getSettings_createsDefaultWhenNoneExist() {
        SiteSettings settings = settingsService.getSettings();

        assertNotNull(settings);
        assertTrue(settings.isAllowAnonymousSuggestions());
        assertTrue(settings.isAllowVoting());
        assertEquals(1440, settings.getSuggestionTimeoutMinutes());
    }

    @Test
    void getSettings_returnsSameInstance() {
        SiteSettings first = settingsService.getSettings();
        SiteSettings second = settingsService.getSettings();

        assertEquals(first.getId(), second.getId());
    }

    @Test
    void updateSettings_updatesValues() {
        SiteSettings update = new SiteSettings();
        update.setAllowAnonymousSuggestions(false);
        update.setAllowVoting(false);
        update.setTargetRepoUrl("https://github.com/test/repo.git");
        update.setSuggestionTimeoutMinutes(720);
        update.setSiteName("My Site");
        update.setRequireApproval(false);

        SiteSettings result = settingsService.updateSettings(update);

        assertFalse(result.isAllowAnonymousSuggestions());
        assertFalse(result.isAllowVoting());
        assertEquals("https://github.com/test/repo.git", result.getTargetRepoUrl());
        assertEquals(720, result.getSuggestionTimeoutMinutes());
        assertEquals("My Site", result.getSiteName());
        assertFalse(result.isRequireApproval());
    }

    @Test
    void updateSettings_slackWebhookUrl_savedAndRetrieved() {
        SiteSettings update = new SiteSettings();
        update.setSlackWebhookUrl("https://hooks.slack.com/services/T00/B00/xxx");

        SiteSettings result = settingsService.updateSettings(update);

        assertEquals("https://hooks.slack.com/services/T00/B00/xxx", result.getSlackWebhookUrl());
    }

    @Test
    void updateSettings_slackWebhookUrl_clearedWhenSetToNull() {
        SiteSettings withUrl = new SiteSettings();
        withUrl.setSlackWebhookUrl("https://hooks.slack.com/services/T00/B00/xxx");
        settingsService.updateSettings(withUrl);

        SiteSettings clearUrl = new SiteSettings();
        clearUrl.setSlackWebhookUrl(null);
        SiteSettings result = settingsService.updateSettings(clearUrl);

        assertNull(result.getSlackWebhookUrl());
    }

    @Test
    void getSettings_slackWebhookUrl_nullByDefault() {
        SiteSettings settings = settingsService.getSettings();

        assertNull(settings.getSlackWebhookUrl());
    }

    @Test
    void getSettings_autoMergePr_falseByDefault() {
        SiteSettings settings = settingsService.getSettings();

        assertFalse(settings.isAutoMergePr());
    }

    @Test
    void updateSettings_autoMergePr_canBeEnabled() {
        SiteSettings update = new SiteSettings();
        update.setAutoMergePr(true);

        SiteSettings result = settingsService.updateSettings(update);

        assertTrue(result.isAutoMergePr());
    }

    @Test
    void updateSettings_autoMergePr_canBeDisabledAfterEnabled() {
        SiteSettings enable = new SiteSettings();
        enable.setAutoMergePr(true);
        settingsService.updateSettings(enable);

        SiteSettings disable = new SiteSettings();
        disable.setAutoMergePr(false);
        SiteSettings result = settingsService.updateSettings(disable);

        assertFalse(result.isAutoMergePr());
    }

    @Test
    void getSettings_requireRegistrationApproval_falseByDefault() {
        SiteSettings settings = settingsService.getSettings();

        assertFalse(settings.isRequireRegistrationApproval());
    }

    @Test
    void updateSettings_requireRegistrationApproval_canBeEnabled() {
        SiteSettings update = new SiteSettings();
        update.setRequireRegistrationApproval(true);

        SiteSettings result = settingsService.updateSettings(update);

        assertTrue(result.isRequireRegistrationApproval());
    }

    @Test
    void updateSettings_requireRegistrationApproval_canBeDisabled() {
        SiteSettings enable = new SiteSettings();
        enable.setRequireRegistrationApproval(true);
        settingsService.updateSettings(enable);

        SiteSettings disable = new SiteSettings();
        disable.setRequireRegistrationApproval(false);
        SiteSettings result = settingsService.updateSettings(disable);

        assertFalse(result.isRequireRegistrationApproval());
    }

    @Test
    void getSettings_registrationsEnabled_trueByDefault() {
        SiteSettings settings = settingsService.getSettings();

        assertTrue(settings.isRegistrationsEnabled());
    }

    @Test
    void updateSettings_registrationsEnabled_canBeDisabled() {
        SiteSettings update = new SiteSettings();
        update.setRegistrationsEnabled(false);

        SiteSettings result = settingsService.updateSettings(update);

        assertFalse(result.isRegistrationsEnabled());
    }

    @Test
    void updateSettings_registrationsEnabled_canBeReEnabled() {
        SiteSettings disable = new SiteSettings();
        disable.setRegistrationsEnabled(false);
        settingsService.updateSettings(disable);

        SiteSettings enable = new SiteSettings();
        enable.setRegistrationsEnabled(true);
        SiteSettings result = settingsService.updateSettings(enable);

        assertTrue(result.isRegistrationsEnabled());
    }

    @Test
    void generateSshKeyPair_populatesPrivateAndPublicKeys() {
        SiteSettings result = settingsService.generateSshKeyPair();

        assertNotNull(result.getGitSshKey());
        assertTrue(result.getGitSshKey().startsWith("-----BEGIN OPENSSH PRIVATE KEY-----\n"));
        assertNotNull(result.getGitSshPublicKey());
        assertTrue(result.getGitSshPublicKey().startsWith("ssh-ed25519 "));
        assertTrue(result.hasGitSshKey());
    }

    @Test
    void generateSshKeyPair_replacesPreviouslyStoredKey() {
        SiteSettings first = settingsService.generateSshKeyPair();
        SiteSettings second = settingsService.generateSshKeyPair();

        assertNotEquals(first.getGitSshKey(), second.getGitSshKey());
        assertNotEquals(first.getGitSshPublicKey(), second.getGitSshPublicKey());
    }

    @Test
    void updateSettings_pastedPrivateKey_clearsStoredPublicKey() {
        // Generate a paired key first so a public key is stored
        settingsService.generateSshKeyPair();
        assertNotNull(settingsService.getSettings().getGitSshPublicKey());

        // User pastes their own private key — the stored public key should be cleared
        // because we can't verify it matches the new private key.
        SiteSettings paste = new SiteSettings();
        paste.setGitSshKey("-----BEGIN OPENSSH PRIVATE KEY-----\nfake-pasted\n-----END OPENSSH PRIVATE KEY-----");
        SiteSettings result = settingsService.updateSettings(paste);

        assertEquals(
                "-----BEGIN OPENSSH PRIVATE KEY-----\nfake-pasted\n-----END OPENSSH PRIVATE KEY-----",
                result.getGitSshKey());
        assertNull(result.getGitSshPublicKey());
    }

    @Test
    void getSettings_spendingLimits_unsetByDefault() {
        SiteSettings settings = settingsService.getSettings();

        assertNull(settings.getMaxCostPerSuggestionUsd());
        assertNull(settings.getMaxTotalCostUsd());
        assertEquals(CostResetPeriod.NEVER, settings.getGlobalCostResetPeriod());
    }

    @Test
    void updateSettings_perSuggestionLimit_persisted() {
        SiteSettings update = new SiteSettings();
        update.setMaxCostPerSuggestionUsd(new BigDecimal("5.25"));

        SiteSettings result = settingsService.updateSettings(update);

        assertEquals(0, new BigDecimal("5.25").compareTo(result.getMaxCostPerSuggestionUsd()));
        assertEquals(0, new BigDecimal("5.25").compareTo(
                settingsService.getSettings().getMaxCostPerSuggestionUsd()));
    }

    @Test
    void updateSettings_totalLimit_persisted() {
        SiteSettings update = new SiteSettings();
        update.setMaxTotalCostUsd(new BigDecimal("100.00"));

        SiteSettings result = settingsService.updateSettings(update);

        assertEquals(0, new BigDecimal("100.00").compareTo(result.getMaxTotalCostUsd()));
    }

    @Test
    void updateSettings_globalResetPeriod_canBeSetToDaily() {
        SiteSettings update = new SiteSettings();
        update.setGlobalCostResetPeriod(CostResetPeriod.DAILY);

        SiteSettings result = settingsService.updateSettings(update);

        assertEquals(CostResetPeriod.DAILY, result.getGlobalCostResetPeriod());
    }

    @Test
    void updateSettings_globalResetPeriod_canBeSetToMonthly() {
        SiteSettings update = new SiteSettings();
        update.setGlobalCostResetPeriod(CostResetPeriod.MONTHLY);

        SiteSettings result = settingsService.updateSettings(update);

        assertEquals(CostResetPeriod.MONTHLY, result.getGlobalCostResetPeriod());
    }

    @Test
    void updateSettings_globalResetPeriod_canBeChangedBackToNever() {
        SiteSettings first = new SiteSettings();
        first.setGlobalCostResetPeriod(CostResetPeriod.MONTHLY);
        settingsService.updateSettings(first);

        SiteSettings second = new SiteSettings();
        second.setGlobalCostResetPeriod(CostResetPeriod.NEVER);
        SiteSettings result = settingsService.updateSettings(second);

        assertEquals(CostResetPeriod.NEVER, result.getGlobalCostResetPeriod());
    }

    @Test
    void updateSettings_globalResetPeriod_nullDefaultsToNever() {
        SiteSettings update = new SiteSettings();
        update.setGlobalCostResetPeriod(null);

        SiteSettings result = settingsService.updateSettings(update);

        assertEquals(CostResetPeriod.NEVER, result.getGlobalCostResetPeriod());
    }

    @Test
    void updateSettings_perSuggestionLimit_canBeClearedWithNull() {
        SiteSettings first = new SiteSettings();
        first.setMaxCostPerSuggestionUsd(new BigDecimal("5.00"));
        settingsService.updateSettings(first);

        SiteSettings clear = new SiteSettings();
        clear.setMaxCostPerSuggestionUsd(null);
        SiteSettings result = settingsService.updateSettings(clear);

        assertNull(result.getMaxCostPerSuggestionUsd());
    }

    @Test
    void updateSettings_totalLimit_canBeClearedWithNull() {
        SiteSettings first = new SiteSettings();
        first.setMaxTotalCostUsd(new BigDecimal("250.00"));
        settingsService.updateSettings(first);

        SiteSettings clear = new SiteSettings();
        clear.setMaxTotalCostUsd(null);
        SiteSettings result = settingsService.updateSettings(clear);

        assertNull(result.getMaxTotalCostUsd());
    }

    @Test
    void updateSettings_perSuggestionLimit_canBeAdjustedRepeatedly() {
        SiteSettings first = new SiteSettings();
        first.setMaxCostPerSuggestionUsd(new BigDecimal("1.00"));
        settingsService.updateSettings(first);

        SiteSettings second = new SiteSettings();
        second.setMaxCostPerSuggestionUsd(new BigDecimal("2.50"));
        settingsService.updateSettings(second);

        SiteSettings third = new SiteSettings();
        third.setMaxCostPerSuggestionUsd(new BigDecimal("10.00"));
        SiteSettings result = settingsService.updateSettings(third);

        assertEquals(0, new BigDecimal("10.00").compareTo(result.getMaxCostPerSuggestionUsd()));
    }

    @Test
    void updateSettings_perSuggestionLimit_zeroAllowed() {
        SiteSettings update = new SiteSettings();
        update.setMaxCostPerSuggestionUsd(BigDecimal.ZERO);

        SiteSettings result = settingsService.updateSettings(update);

        assertEquals(0, BigDecimal.ZERO.compareTo(result.getMaxCostPerSuggestionUsd()));
    }

    @Test
    void updateSettings_perSuggestionLimit_negativeRejected() {
        SiteSettings update = new SiteSettings();
        update.setMaxCostPerSuggestionUsd(new BigDecimal("-1.00"));

        assertThrows(IllegalArgumentException.class, () -> settingsService.updateSettings(update));
    }

    @Test
    void updateSettings_totalLimit_negativeRejected() {
        SiteSettings update = new SiteSettings();
        update.setMaxTotalCostUsd(new BigDecimal("-0.01"));

        assertThrows(IllegalArgumentException.class, () -> settingsService.updateSettings(update));
    }

    @Test
    void updateSettings_negativeLimit_doesNotMutateStoredValue() {
        SiteSettings first = new SiteSettings();
        first.setMaxCostPerSuggestionUsd(new BigDecimal("3.00"));
        settingsService.updateSettings(first);

        SiteSettings bad = new SiteSettings();
        bad.setMaxCostPerSuggestionUsd(new BigDecimal("-5.00"));
        bad.setSiteName("Should Not Save");
        assertThrows(IllegalArgumentException.class, () -> settingsService.updateSettings(bad));

        SiteSettings reloaded = settingsService.getSettings();
        assertEquals(0, new BigDecimal("3.00").compareTo(reloaded.getMaxCostPerSuggestionUsd()));
        assertNotEquals("Should Not Save", reloaded.getSiteName());
    }

    @Test
    void updateSettings_spendingLimits_supportSubCentPrecision() {
        SiteSettings update = new SiteSettings();
        update.setMaxCostPerSuggestionUsd(new BigDecimal("0.001234"));
        update.setMaxTotalCostUsd(new BigDecimal("0.567890"));

        SiteSettings result = settingsService.updateSettings(update);

        assertEquals(0, new BigDecimal("0.001234").compareTo(result.getMaxCostPerSuggestionUsd()));
        assertEquals(0, new BigDecimal("0.567890").compareTo(result.getMaxTotalCostUsd()));
    }

    @Test
    void updateSettings_gitSshKeyOmitted_preservesGeneratedPublicKey() {
        settingsService.generateSshKeyPair();
        String storedPublic = settingsService.getSettings().getGitSshPublicKey();
        assertNotNull(storedPublic);

        // An update that does not touch the SSH key should leave the public key alone
        SiteSettings update = new SiteSettings();
        update.setSiteName("Renamed");
        // gitSshKey left null → preserve existing pair
        SiteSettings result = settingsService.updateSettings(update);

        assertEquals(storedPublic, result.getGitSshPublicKey());
    }

    // ── Spending alert configuration ─────────────────────────────────────────

    @Test
    void getSettings_spendingAlertsEnabled_trueByDefault() {
        SiteSettings settings = settingsService.getSettings();
        assertTrue(settings.isSpendingAlertsEnabled());
    }

    @Test
    void getSettings_spendingAlertThresholds_defaultsTo75And90() {
        SiteSettings settings = settingsService.getSettings();
        assertEquals("75,90", settings.getSpendingAlertThresholds());
    }

    @Test
    void getSettings_spendingAlertRecipients_nullByDefault() {
        SiteSettings settings = settingsService.getSettings();
        assertNull(settings.getSpendingAlertRecipients());
    }

    @Test
    void updateSettings_spendingAlertsEnabled_canBeDisabled() {
        SiteSettings update = new SiteSettings();
        update.setSpendingAlertsEnabled(false);

        SiteSettings result = settingsService.updateSettings(update);

        assertFalse(result.isSpendingAlertsEnabled());
    }

    @Test
    void updateSettings_spendingAlertThresholds_canBeChanged() {
        SiteSettings update = new SiteSettings();
        update.setSpendingAlertThresholds("60,80,95");

        SiteSettings result = settingsService.updateSettings(update);

        assertEquals("60,80,95", result.getSpendingAlertThresholds());
    }

    @Test
    void updateSettings_spendingAlertRecipients_persistedFromServerSideConfig() {
        SiteSettings update = new SiteSettings();
        update.setSpendingAlertRecipients("oncall@example.com\ncarol");

        SiteSettings result = settingsService.updateSettings(update);

        assertEquals("oncall@example.com\ncarol", result.getSpendingAlertRecipients());
    }
}
