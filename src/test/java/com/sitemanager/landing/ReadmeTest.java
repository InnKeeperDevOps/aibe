package com.sitemanager.landing;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Validates the structure of landing_site/README.md.
 * These tests are plain file-read checks — no browser required.
 */
class ReadmeTest {

    private static String md;

    @BeforeAll
    static void loadFile() throws IOException {
        Path path = Paths.get("landing_site", "README.md");
        assertTrue(Files.exists(path), "landing_site/README.md must exist");
        md = Files.readString(path);
    }

    @Test
    void hasH1Title() {
        assertTrue(md.contains("# ") && md.toLowerCase().contains("aibe"),
                "README must have an H1 heading mentioning Aibe");
    }

    @Test
    void hasPrerequisitesSection() {
        assertTrue(md.contains("## Prerequisites"),
                "README must have a ## Prerequisites section");
    }

    @Test
    void hasInstallationSection() {
        assertTrue(md.contains("## Installation"),
                "README must have a ## Installation section");
    }

    @Test
    void hasUserSignupsSection() {
        assertTrue(md.contains("## Configuring User Signups") || md.contains("User Signups"),
                "README must have a User Signups section");
    }

    @Test
    void hasReviewingSuggestionsSection() {
        assertTrue(md.contains("## Reviewing Suggestions"),
                "README must have a ## Reviewing Suggestions section");
    }

    @Test
    void hasSlackNotificationsSection() {
        assertTrue(md.contains("## Setting Up Slack Notifications") || md.contains("Slack Notifications"),
                "README must have a Slack Notifications section");
    }

    @Test
    void hasJdkInstallCommands() {
        assertTrue(md.contains("openjdk-17") || md.contains("java-17"),
                "Prerequisites must include JDK 17 install commands");
    }

    @Test
    void hasJavaVersionCheck() {
        assertTrue(md.contains("java -version"),
                "Prerequisites must include `java -version` verification command");
    }

    @Test
    void hasClaudeCliInstall() {
        assertTrue(md.contains("npm install -g @anthropic-ai/claude-code"),
                "Prerequisites must include the Claude CLI npm install command");
    }

    @Test
    void hasApiKeySetup() {
        assertTrue(md.contains("ANTHROPIC_API_KEY"),
                "Prerequisites must include ANTHROPIC_API_KEY setup instructions");
    }

    @Test
    void hasClaudeVersionCheck() {
        assertTrue(md.contains("claude --version"),
                "Prerequisites must include `claude --version` check");
    }

    @Test
    void hasNodeJsPrerequisite() {
        assertTrue(md.contains("Node.js") || md.contains("node"),
                "Prerequisites must mention Node.js as a dependency");
    }

    @Test
    void hasGitCloneCommand() {
        assertTrue(md.contains("git clone"),
                "Installation section must include git clone command");
    }

    @Test
    void hasBuildCommands() {
        assertTrue(md.contains("./gradlew build"),
                "Installation section must include Gradle build command");
        assertTrue(md.contains("mvn package -DskipTests"),
                "Installation section must include Maven build command");
    }

    @Test
    void hasRunCommand() {
        assertTrue(md.contains("java -jar") && md.contains("site-manager"),
                "Installation section must include the java -jar run command");
    }

    @Test
    void mentionsDefaultPort() {
        assertTrue(md.contains("8080"),
                "Installation section must mention the default port 8080");
    }

    @Test
    void hasRootWarning() {
        assertTrue(md.contains("root"),
                "Installation section must include a warning about not running as root");
        // Should be a prominent block, not just inline
        assertTrue(md.contains("> **") || md.contains("WARNING") || md.contains("⚠"),
                "Root warning must be presented as a prominent block or callout");
    }

    @Test
    void rootWarningExplainsFilesystemRisk() {
        int rootIdx = md.indexOf("root");
        assertTrue(rootIdx >= 0);
        // Find the warning block context
        String around = md.substring(Math.max(0, rootIdx - 50), Math.min(md.length(), rootIdx + 500));
        assertTrue(around.toLowerCase().contains("filesystem") || around.toLowerCase().contains("file"),
                "Root warning must explain the filesystem access risk");
    }

    @Test
    void hasUserGroupsDescription() {
        assertTrue(md.contains("Admin") && md.contains("Regular"),
                "User signups section must describe Admin and Regular user groups");
    }

    @Test
    void hasInviteOnlyWorkflow() {
        assertTrue(md.toLowerCase().contains("invite"),
                "User signups section must describe the invite-only workflow");
    }

    @Test
    void hasSlackWebhookReference() {
        assertTrue(md.contains("api.slack.com"),
                "Slack section must reference the Slack webhook setup URL");
    }

    @Test
    void hasSlackNotificationEvents() {
        assertTrue(md.contains("PR created") || md.contains("pull request"),
                "Slack section must list PR creation as a notification event");
        assertTrue(md.toLowerCase().contains("approved") || md.toLowerCase().contains("rejected"),
                "Slack section must list suggestion approval/rejection as notification events");
    }

    @Test
    void hasCodeFenceBlocks() {
        assertTrue(md.contains("```"),
                "README must use fenced code blocks for commands");
    }
}
