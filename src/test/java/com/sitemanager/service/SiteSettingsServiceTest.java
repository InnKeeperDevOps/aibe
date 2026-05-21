package com.sitemanager.service;

import com.sitemanager.model.SiteSettings;
import com.sitemanager.repository.SiteSettingsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

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
}
