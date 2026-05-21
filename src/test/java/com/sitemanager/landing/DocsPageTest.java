package com.sitemanager.landing;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Validates the structure of landing_site/docs.html.
 * These tests are plain file-read checks — no server required.
 */
class DocsPageTest {

    private static String html;

    @BeforeAll
    static void loadFile() throws IOException {
        Path path = Paths.get("landing_site", "docs.html");
        assertTrue(Files.exists(path), "landing_site/docs.html must exist");
        html = Files.readString(path);
    }

    @Test
    void hasDoctype() {
        assertTrue(html.trim().toLowerCase().startsWith("<!doctype html"),
                "Page must begin with <!DOCTYPE html>");
    }

    @Test
    void hasViewportMeta() {
        assertTrue(html.contains("name=\"viewport\""),
                "Page must contain a viewport meta tag");
    }

    @Test
    void titleContainsAibe() {
        assertTrue(html.contains("<title>") && html.toLowerCase().contains("aibe"),
                "Page <title> must mention Aibe");
    }

    @Test
    void linksSharedStylesheet() {
        assertTrue(html.contains("css/styles.css"),
                "Page must link to shared css/styles.css");
    }

    @Test
    void hasDocsNav() {
        assertTrue(html.contains("class=\"docs-nav\""),
                "Page must include a <nav> with class 'docs-nav' for in-page navigation");
    }

    @Test
    void navHasPrerequisitesAnchor() {
        assertTrue(html.contains("#prerequisites"),
                "Docs nav must link to #prerequisites section");
    }

    @Test
    void navHasInstallationAnchor() {
        assertTrue(html.contains("#installation"),
                "Docs nav must link to #installation section");
    }

    @Test
    void navHasUserSignupsAnchor() {
        assertTrue(html.contains("#user-signups"),
                "Docs nav must link to #user-signups section");
    }

    @Test
    void navHasReviewingSuggestionsAnchor() {
        assertTrue(html.contains("#reviewing-suggestions"),
                "Docs nav must link to #reviewing-suggestions section");
    }

    @Test
    void navHasSlackNotificationsAnchor() {
        assertTrue(html.contains("#slack-notifications"),
                "Docs nav must link to #slack-notifications section");
    }

    @Test
    void prerequisitesSectionPresent() {
        assertTrue(html.contains("id=\"prerequisites\""),
                "Page must have a section with id 'prerequisites'");
    }

    @Test
    void installationSectionPresent() {
        assertTrue(html.contains("id=\"installation\""),
                "Page must have a section with id 'installation'");
    }

    @Test
    void userSignupsSectionPresent() {
        assertTrue(html.contains("id=\"user-signups\""),
                "Page must have a section with id 'user-signups'");
    }

    @Test
    void reviewingSuggestionsSectionPresent() {
        assertTrue(html.contains("id=\"reviewing-suggestions\""),
                "Page must have a section with id 'reviewing-suggestions'");
    }

    @Test
    void slackNotificationsSectionPresent() {
        assertTrue(html.contains("id=\"slack-notifications\""),
                "Page must have a section with id 'slack-notifications'");
    }

    @Test
    void hasPreCodeBlocks() {
        assertTrue(html.contains("<pre>") && html.contains("<code>"),
                "Page must use <pre><code> blocks for commands");
    }

    @Test
    void rootWarningCalloutPresent() {
        assertTrue(html.contains("class=\"callout\""),
                "Root warning must be rendered as a styled callout div");
    }

    @Test
    void rootWarningMentionsRoot() {
        // Find the callout div content and verify it warns about root
        int calloutIdx = html.indexOf("class=\"callout\"");
        assertTrue(calloutIdx >= 0, "callout div must exist");
        String afterCallout = html.substring(calloutIdx, Math.min(calloutIdx + 600, html.length()));
        assertTrue(afterCallout.toLowerCase().contains("root"),
                "Root warning callout must mention running as root");
    }

    @Test
    void rootWarningMentionsFilesystem() {
        int calloutIdx = html.indexOf("class=\"callout\"");
        assertTrue(calloutIdx >= 0, "callout div must exist");
        String afterCallout = html.substring(calloutIdx, Math.min(calloutIdx + 600, html.length()));
        assertTrue(afterCallout.toLowerCase().contains("filesystem") || afterCallout.toLowerCase().contains("file"),
                "Root warning must explain the filesystem access risk");
    }

    @Test
    void hasInstallCommands() {
        assertTrue(html.contains("./gradlew build") || html.contains("mvn package"),
                "Installation section must include build commands");
    }

    @Test
    void hasSlackWebhookReference() {
        assertTrue(html.contains("api.slack.com"),
                "Slack section must reference the Slack webhook setup page");
    }

    @Test
    void footerLinksToApp() {
        assertTrue(html.contains("https://suggest.aibe.app"),
                "Page must contain a link to suggest.aibe.app");
    }

    @Test
    void footerPresent() {
        assertTrue(html.contains("class=\"site-footer\""),
                "Page must have a footer with class 'site-footer'");
    }

    @Test
    void noExternalJsFrameworks() {
        String lower = html.toLowerCase();
        assertFalse(lower.contains("react") || lower.contains("vue") || lower.contains("angular"),
                "Page must not load external JS frameworks");
    }
}
