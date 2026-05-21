# Aibe.app — Documentation

Self-hosted AI suggestion management — installation and configuration guide.

---

## Table of Contents

- [Prerequisites](#prerequisites)
- [Installation](#installation)
- [Configuring User Signups](#configuring-user-signups)
- [Reviewing Suggestions](#reviewing-suggestions)
- [Setting Up Slack Notifications](#setting-up-slack-notifications)

---

## Prerequisites

### JDK 17

Install JDK 17 for your platform:

```bash
# Debian / Ubuntu
sudo apt install openjdk-17-jdk

# Fedora / RHEL / Rocky
sudo dnf install java-17-openjdk-devel

# macOS (Homebrew)
brew install openjdk@17
```

Verify the installation:

```bash
java -version
```

### Node.js 18+

Node.js 18 or later is required as a dependency for the Claude CLI. Install it via your package manager or from `nodejs.org`.

```bash
# Verify
node --version   # should print v18.x.x or higher
```

### Claude CLI

Install the Claude CLI globally with npm:

```bash
npm install -g @anthropic-ai/claude-code
```

Set your Anthropic API key as an environment variable:

```bash
export ANTHROPIC_API_KEY=your_api_key_here
```

Add that line to your shell profile (`~/.bashrc`, `~/.zshrc`, etc.) to make it permanent. Verify the CLI is working:

```bash
claude --version
```

---

## Installation

### Clone the repository

```bash
git clone https://github.com/InnKeeperDevOps/suggestion-manager.git
cd suggestion-manager
```

### Build

Using Gradle (default):

```bash
./gradlew build
```

Or using Maven:

```bash
mvn package -DskipTests
```

### Run

```bash
java -jar build/libs/site-manager-*.jar
```

The application starts on port `8080` by default. Open `http://localhost:8080` in your browser.

> **⚠️ WARNING — Do not run as root**
>
> Running this application as the `root` user gives the AI agent unrestricted filesystem access across your entire machine. This defeats OS-level process isolation and means a misbehaving or adversarial suggestion could read, modify, or delete any file on the system. Always run as a dedicated, unprivileged user account.

---

## Configuring User Signups

1. Log in as an admin account.
2. Navigate to the **Settings** tab.
3. Toggle **Allow User Registrations** to enable or disable new account creation.

### User groups

- **Admin** — full access: manage users, review suggestions, change settings.
- **Regular users** — can submit suggestions and vote; cannot administer the instance.

### Invite-only workflow

When registration is disabled, only admin-created accounts can log in. Share credentials directly with trusted users to keep your instance invite-only.

---

## Reviewing Suggestions

1. Incoming suggestions appear in the **suggestion list** on the main dashboard.
2. Each suggestion is automatically evaluated by **Claude AI** for technical feasibility and priority.
3. An admin can **approve** a suggestion — this triggers an automated git clone and GitHub pull request — or **reject** it with an optional reason.
4. **Expert review mode** adds an extra human-oversight step before any code execution begins.
5. **Real-time status updates** are pushed to all connected browsers via WebSocket — no page refresh needed.

---

## Setting Up Slack Notifications

1. In your Slack workspace, create an **Incoming Webhook** at `api.slack.com/messaging/webhooks`.
2. Copy the webhook URL provided by Slack.
3. In Aibe.app, go to **Settings → Slack Webhook URL**, paste the URL, and save.

### Notifications sent for

- New suggestion submitted
- Suggestion approved or rejected
- GitHub pull request created
- Execution complete
