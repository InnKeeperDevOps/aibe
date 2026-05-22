# Changelog

## [Unreleased]

### Added
- **Changelog & PR Workflow**: When a suggestion's implementation completes, the platform now automatically:
  - Pushes the suggestion branch to the remote repository
  - Generates a changelog entry from the suggestion metadata (title, author, description, plan)
  - Creates a GitHub Pull Request with the changelog and a link back to the suggestion
  - Stores the PR URL and number on the suggestion for easy reference
- **PR link in UI**: Completed suggestions now display a clickable link to the created Pull Request
- **Changelog display**: Completed suggestions show the auto-generated changelog entry in the detail view
- **GitHub Token setting**: New "GitHub Token" field in Settings to enable automatic PR creation (optional — branch push still works without it)
- **Real-time PR notification**: WebSocket `pr_created` event updates the UI immediately when a PR is created
- **Claude CLI Logs (admin)**: New admin-only "Claude Logs" page listing every Claude CLI invocation — the full prompt sent and the raw output returned, with command, model, exit code and duration. Backed by a new `claude_cli_logs` table populated on every CLI call (`GET /api/claude-logs`)

### Changed
- `handleExecutionResult` now triggers the push/PR workflow asynchronously after execution completes
- Suggestion model extended with `prUrl`, `prNumber`, and `changelogEntry` fields
- SiteSettings model extended with `githubToken` field
- ClaudeService extended with `pushBranch`, `createGitHubPullRequest`, and `getCommitLog` methods
