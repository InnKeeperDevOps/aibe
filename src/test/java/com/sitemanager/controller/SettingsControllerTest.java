package com.sitemanager.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitemanager.repository.SiteSettingsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class SettingsControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private SiteSettingsRepository settingsRepository;

    @BeforeEach
    void setUp() {
        settingsRepository.deleteAll();
    }

    @Test
    void getSettings_returnsDefaults() throws Exception {
        mockMvc.perform(get("/api/settings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowAnonymousSuggestions").value(true))
                .andExpect(jsonPath("$.allowVoting").value(true));
    }

    @Test
    void updateSettings_asAdmin_succeeds() throws Exception {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("username", "admin");
        session.setAttribute("role", "ROOT_ADMIN");

        mockMvc.perform(put("/api/settings")
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"allowAnonymousSuggestions\":false,\"allowVoting\":false," +
                                "\"targetRepoUrl\":\"https://github.com/test/repo\"," +
                                "\"suggestionTimeoutMinutes\":60,\"requireApproval\":true," +
                                "\"siteName\":\"Test Site\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowAnonymousSuggestions").value(false))
                .andExpect(jsonPath("$.siteName").value("Test Site"));
    }

    @Test
    void updateSettings_asNonAdmin_returns403() throws Exception {
        mockMvc.perform(put("/api/settings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"allowVoting\":false}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void updateSettings_asRegularAdmin_succeeds() throws Exception {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("username", "admin2");
        session.setAttribute("role", "ADMIN");

        mockMvc.perform(put("/api/settings")
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"allowAnonymousSuggestions\":true,\"allowVoting\":true," +
                                "\"suggestionTimeoutMinutes\":1440,\"requireApproval\":true," +
                                "\"siteName\":\"Updated\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void updateSettings_slackWebhookUrl_savedAndReturnedInResponse() throws Exception {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("username", "admin");
        session.setAttribute("role", "ROOT_ADMIN");

        mockMvc.perform(put("/api/settings")
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"allowAnonymousSuggestions\":true,\"allowVoting\":true," +
                                "\"suggestionTimeoutMinutes\":1440,\"requireApproval\":true," +
                                "\"siteName\":\"Test\"," +
                                "\"slackWebhookUrl\":\"https://hooks.slack.com/services/T00/B00/xxx\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slackWebhookUrl").value("https://hooks.slack.com/services/T00/B00/xxx"));
    }

    @Test
    void getSettings_slackWebhookUrl_presentInResponse() throws Exception {
        mockMvc.perform(get("/api/settings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slackWebhookUrl").doesNotExist());
    }

    @Test
    void getSettings_requireRegistrationApproval_falseByDefault() throws Exception {
        mockMvc.perform(get("/api/settings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requireRegistrationApproval").value(false));
    }

    @Test
    void updateSettings_requireRegistrationApproval_persistedAndReturnedInResponse() throws Exception {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("username", "admin");
        session.setAttribute("role", "ROOT_ADMIN");

        mockMvc.perform(put("/api/settings")
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"allowAnonymousSuggestions\":true,\"allowVoting\":true," +
                                "\"suggestionTimeoutMinutes\":1440,\"requireApproval\":true," +
                                "\"siteName\":\"Test\",\"requireRegistrationApproval\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requireRegistrationApproval").value(true));
    }

    @Test
    void updateSettings_requireRegistrationApproval_canBeDisabledAfterEnabled() throws Exception {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("username", "admin");
        session.setAttribute("role", "ROOT_ADMIN");

        String enableBody = "{\"allowAnonymousSuggestions\":true,\"allowVoting\":true," +
                "\"suggestionTimeoutMinutes\":1440,\"requireApproval\":true," +
                "\"siteName\":\"Test\",\"requireRegistrationApproval\":true}";
        mockMvc.perform(put("/api/settings").session(session)
                        .contentType(MediaType.APPLICATION_JSON).content(enableBody))
                .andExpect(status().isOk());

        String disableBody = "{\"allowAnonymousSuggestions\":true,\"allowVoting\":true," +
                "\"suggestionTimeoutMinutes\":1440,\"requireApproval\":true," +
                "\"siteName\":\"Test\",\"requireRegistrationApproval\":false}";
        mockMvc.perform(put("/api/settings").session(session)
                        .contentType(MediaType.APPLICATION_JSON).content(disableBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requireRegistrationApproval").value(false));
    }

    @Test
    void getSettings_doesNotIncludeGitSshKey_andHasGitSshKeyIsFalseByDefault() throws Exception {
        mockMvc.perform(get("/api/settings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gitSshKey").doesNotExist())
                .andExpect(jsonPath("$.hasGitSshKey").value(false));
    }

    @Test
    void updateSettings_gitSshKey_isPersistedButNotEchoedBack() throws Exception {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("username", "admin");
        session.setAttribute("role", "ROOT_ADMIN");

        String key = "-----BEGIN OPENSSH PRIVATE KEY-----\nfake\n-----END OPENSSH PRIVATE KEY-----";
        String body = "{\"allowAnonymousSuggestions\":true,\"allowVoting\":true," +
                "\"suggestionTimeoutMinutes\":1440,\"requireApproval\":true," +
                "\"siteName\":\"Test\",\"gitSshKey\":\"" + key.replace("\n", "\\n") + "\"}";

        mockMvc.perform(put("/api/settings").session(session)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gitSshKey").doesNotExist())
                .andExpect(jsonPath("$.hasGitSshKey").value(true));

        mockMvc.perform(get("/api/settings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gitSshKey").doesNotExist())
                .andExpect(jsonPath("$.hasGitSshKey").value(true));
    }

    @Test
    void updateSettings_gitSshKey_omittedOrNull_preservesExistingKey() throws Exception {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("username", "admin");
        session.setAttribute("role", "ROOT_ADMIN");

        String firstBody = "{\"allowAnonymousSuggestions\":true,\"allowVoting\":true," +
                "\"suggestionTimeoutMinutes\":1440,\"requireApproval\":true," +
                "\"siteName\":\"Test\",\"gitSshKey\":\"ssh-key-content\"}";
        mockMvc.perform(put("/api/settings").session(session)
                        .contentType(MediaType.APPLICATION_JSON).content(firstBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasGitSshKey").value(true));

        // Second update with gitSshKey omitted should keep the previously stored key
        String secondBody = "{\"allowAnonymousSuggestions\":true,\"allowVoting\":true," +
                "\"suggestionTimeoutMinutes\":1440,\"requireApproval\":true," +
                "\"siteName\":\"Updated Site\"}";
        mockMvc.perform(put("/api/settings").session(session)
                        .contentType(MediaType.APPLICATION_JSON).content(secondBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.siteName").value("Updated Site"))
                .andExpect(jsonPath("$.hasGitSshKey").value(true));

        // Explicit null is also treated as "do not change"
        String nullBody = "{\"allowAnonymousSuggestions\":true,\"allowVoting\":true," +
                "\"suggestionTimeoutMinutes\":1440,\"requireApproval\":true," +
                "\"siteName\":\"Updated Site\",\"gitSshKey\":null}";
        mockMvc.perform(put("/api/settings").session(session)
                        .contentType(MediaType.APPLICATION_JSON).content(nullBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasGitSshKey").value(true));
    }

    @Test
    void updateSettings_gitSshKey_blankString_clearsKey() throws Exception {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("username", "admin");
        session.setAttribute("role", "ROOT_ADMIN");

        String firstBody = "{\"allowAnonymousSuggestions\":true,\"allowVoting\":true," +
                "\"suggestionTimeoutMinutes\":1440,\"requireApproval\":true," +
                "\"siteName\":\"Test\",\"gitSshKey\":\"ssh-key-content\"}";
        mockMvc.perform(put("/api/settings").session(session)
                        .contentType(MediaType.APPLICATION_JSON).content(firstBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasGitSshKey").value(true));

        // Empty string explicitly clears
        String clearBody = "{\"allowAnonymousSuggestions\":true,\"allowVoting\":true," +
                "\"suggestionTimeoutMinutes\":1440,\"requireApproval\":true," +
                "\"siteName\":\"Test\",\"gitSshKey\":\"\"}";
        mockMvc.perform(put("/api/settings").session(session)
                        .contentType(MediaType.APPLICATION_JSON).content(clearBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasGitSshKey").value(false));
    }

    @Test
    void getSettings_requireRegistrationApproval_reflectsPersistedValue() throws Exception {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("username", "admin");
        session.setAttribute("role", "ROOT_ADMIN");

        mockMvc.perform(put("/api/settings").session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"allowAnonymousSuggestions\":true,\"allowVoting\":true," +
                                "\"suggestionTimeoutMinutes\":1440,\"requireApproval\":true," +
                                "\"siteName\":\"Test\",\"requireRegistrationApproval\":true}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/settings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requireRegistrationApproval").value(true));
    }
}
