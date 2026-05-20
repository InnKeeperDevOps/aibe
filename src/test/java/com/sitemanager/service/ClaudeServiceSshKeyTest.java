package com.sitemanager.service;

import com.sitemanager.model.SiteSettings;
import com.sitemanager.repository.SiteSettingsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ClaudeServiceSshKeyTest {

    @Mock
    private SiteSettingsRepository settingsRepository;

    @InjectMocks
    private ClaudeService claudeService;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(claudeService, "workspaceDir", tempDir.toString());
        ReflectionTestUtils.setField(claudeService, "claudeRunAsUser", "");
    }

    @Test
    void returnsNull_whenNoSettingsKeyConfigured() {
        when(settingsRepository.findAll()).thenReturn(List.of(new SiteSettings()));
        assertNull(claudeService.materializeSettingsSshKey());
    }

    @Test
    void returnsNull_whenSettingsKeyIsBlank() {
        SiteSettings settings = new SiteSettings();
        settings.setGitSshKey("   ");
        when(settingsRepository.findAll()).thenReturn(List.of(settings));
        assertNull(claudeService.materializeSettingsSshKey());
    }

    @Test
    void writesKeyToFile_whenSettingsKeyConfigured() throws Exception {
        SiteSettings settings = new SiteSettings();
        String keyContent = "-----BEGIN OPENSSH PRIVATE KEY-----\nfake-key-body\n-----END OPENSSH PRIVATE KEY-----";
        settings.setGitSshKey(keyContent);
        when(settingsRepository.findAll()).thenReturn(List.of(settings));

        String path = claudeService.materializeSettingsSshKey();

        assertNotNull(path);
        Path keyFile = Path.of(path);
        assertTrue(Files.exists(keyFile));
        String written = Files.readString(keyFile);
        // Should append trailing newline if missing
        assertEquals(keyContent + "\n", written);
    }

    @Test
    void setsRestrictivePermissionsOnKeyFile() throws Exception {
        SiteSettings settings = new SiteSettings();
        settings.setGitSshKey("private-key-content");
        when(settingsRepository.findAll()).thenReturn(List.of(settings));

        String path = claudeService.materializeSettingsSshKey();
        assertNotNull(path);

        try {
            Set<PosixFilePermission> perms = Files.getPosixFilePermissions(Path.of(path));
            // 0600: owner read/write only, no group or others access
            assertTrue(perms.contains(PosixFilePermission.OWNER_READ));
            assertTrue(perms.contains(PosixFilePermission.OWNER_WRITE));
            assertFalse(perms.contains(PosixFilePermission.GROUP_READ));
            assertFalse(perms.contains(PosixFilePermission.GROUP_WRITE));
            assertFalse(perms.contains(PosixFilePermission.OTHERS_READ));
            assertFalse(perms.contains(PosixFilePermission.OTHERS_WRITE));
        } catch (UnsupportedOperationException e) {
            // Non-POSIX filesystem; permissions not enforceable
        }
    }

    @Test
    void rewritesKeyFile_whenContentChanges() throws Exception {
        SiteSettings firstSettings = new SiteSettings();
        firstSettings.setGitSshKey("first-key");
        when(settingsRepository.findAll()).thenReturn(List.of(firstSettings));
        String path = claudeService.materializeSettingsSshKey();
        assertEquals("first-key\n", Files.readString(Path.of(path)));

        SiteSettings secondSettings = new SiteSettings();
        secondSettings.setGitSshKey("second-key");
        when(settingsRepository.findAll()).thenReturn(List.of(secondSettings));
        String secondPath = claudeService.materializeSettingsSshKey();

        assertEquals(path, secondPath);
        assertEquals("second-key\n", Files.readString(Path.of(secondPath)));
    }

    @Test
    void preservesExistingTrailingNewline() throws Exception {
        SiteSettings settings = new SiteSettings();
        String keyContent = "key-with-newline\n";
        settings.setGitSshKey(keyContent);
        when(settingsRepository.findAll()).thenReturn(List.of(settings));

        String path = claudeService.materializeSettingsSshKey();
        assertEquals(keyContent, Files.readString(Path.of(path)));
    }
}
