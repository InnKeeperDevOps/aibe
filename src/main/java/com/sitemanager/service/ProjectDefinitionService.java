package com.sitemanager.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sitemanager.dto.ProjectDefinitionStateResponse;
import com.sitemanager.model.ProjectDefinitionSession;
import com.sitemanager.model.SiteSettings;
import com.sitemanager.model.User;
import com.sitemanager.model.enums.ProjectDefinitionStatus;
import com.sitemanager.model.enums.UserRole;
import com.sitemanager.repository.ProjectDefinitionSessionRepository;
import com.sitemanager.repository.UserRepository;
import com.sitemanager.websocket.UserNotificationWebSocketHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Manual QA smoke-test checklist:
 * <ol>
 *   <li>Log in as an admin and open the Project Definition page.</li>
 *   <li>Click "Start Interview" — confirm the first question appears.</li>
 *   <li>Answer all 9 questions; verify each answer advances the progress bar.</li>
 *   <li>After the last answer, confirm the status transitions to "Generating"
 *       and then "Saving" (visible via the WebSocket status badge).</li>
 *   <li>In the target GitHub repository, verify that
 *       {@code PROJECT_DEFINITION.md} has been committed on a new branch.</li>
 *   <li>Confirm a pull request was created and its URL is shown in the UI.</li>
 *   <li>If auto-merge is enabled in Settings, confirm the PR was merged
 *       automatically and the status shows "Completed".</li>
 *   <li>If auto-merge is disabled, confirm the status shows "PR Open"
 *       with a link to the pull request for manual merging.</li>
 * </ol>
 */
@Service
public class ProjectDefinitionService {

    private static final Logger log = LoggerFactory.getLogger(ProjectDefinitionService.class);

    static final List<ProjectDefinitionStatus> NON_TERMINAL_STATUSES = List.of(
            ProjectDefinitionStatus.ACTIVE,
            ProjectDefinitionStatus.GENERATING,
            ProjectDefinitionStatus.SAVING
    );

    private final ProjectDefinitionSessionRepository sessionRepository;
    private final ClaudeService claudeService;
    private final SiteSettingsService settingsService;
    private final UserNotificationWebSocketHandler notificationHandler;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Value("${app.workspace-dir:/workspace}")
    private String workspaceDir;

    /**
     * Self-reference so the transactional {@link #createSession()} runs through
     * the Spring proxy when invoked from the non-transactional {@link #startSession()}.
     */
    @Autowired
    @Lazy
    private ProjectDefinitionService self;

    public ProjectDefinitionService(ProjectDefinitionSessionRepository sessionRepository,
                                    ClaudeService claudeService,
                                    SiteSettingsService settingsService,
                                    UserNotificationWebSocketHandler notificationHandler,
                                    UserRepository userRepository) {
        this.sessionRepository = sessionRepository;
        this.claudeService = claudeService;
        this.settingsService = settingsService;
        this.notificationHandler = notificationHandler;
        this.userRepository = userRepository;
    }

    /**
     * Atomically guard against concurrent interview sessions and create the
     * ACTIVE session row. Kept deliberately short — it must NOT perform any
     * long-running work (e.g. a Claude CLI call) because it holds a database
     * connection (and a SERIALIZABLE lock) for its entire duration. With a
     * small HikariCP pool, blocking inside this transaction exhausts the pool.
     */
    @Transactional(isolation = Isolation.SERIALIZABLE)
    public ProjectDefinitionSession createSession() {
        if (sessionRepository.findFirstByStatusIn(NON_TERMINAL_STATUSES).isPresent()) {
            throw new IllegalStateException("A project definition session is already in progress");
        }

        ProjectDefinitionSession session = new ProjectDefinitionSession();
        session.setStatus(ProjectDefinitionStatus.ACTIVE);
        session.setClaudeSessionId(claudeService.generateSessionId());
        session.setConversationHistory("[]");
        session.setHasExistingDefinition(readExistingProjectDefinition() != null);
        return sessionRepository.save(session);
    }

    /**
     * Start a new project definition interview session.
     * Throws IllegalStateException if a non-terminal session already exists.
     * <p>
     * The session row is created in a short SERIALIZABLE transaction
     * ({@link #createSession()}); the subsequent Claude CLI call runs WITHOUT
     * an open transaction so it does not pin a database connection for minutes
     * and exhaust the connection pool.
     */
    public ProjectDefinitionStateResponse startSession() {
        ProjectDefinitionSession session = self.createSession();

        final Long sessionId = session.getId();
        final String claudeSessionId = session.getClaudeSessionId();
        final String prompt = buildInterviewPrompt(readExistingProjectDefinition());

        try {
            String response = claudeService.continueConversation(claudeSessionId, prompt, null,
                    claudeService.getMainRepoDir(), null)
                    .get(5, TimeUnit.MINUTES);

            String json = extractJsonBlock(response);
            JsonNode root = null;
            if (json != null) {
                try {
                    root = objectMapper.readTree(json);
                } catch (Exception parseEx) {
                    log.debug("Could not parse Claude response as JSON for session {}: {}",
                            sessionId, parseEx.getMessage());
                }
            }

            session.setConversationHistory(appendMessage(session.getConversationHistory(), "assistant", response));
            session = sessionRepository.save(session);
            broadcastState(session, root);

            return toStateResponseWithQuestion(session, root);
        } catch (Exception e) {
            log.error("Synchronous Claude call failed for session {}: {}", sessionId, e.getMessage(), e);
            markFailed(sessionId, "Something went wrong while starting the interview");
            throw new RuntimeException("Failed to start interview session", e);
        }
    }

    /**
     * Submit an answer to the current interview question.
     * Calls Claude synchronously and returns the next question (or GENERATING state)
     * directly in the HTTP response. WebSocket broadcast is also sent as a secondary update.
     */
    public ProjectDefinitionStateResponse submitAnswer(Long sessionId, String answer) {
        ProjectDefinitionSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Session not found: " + sessionId));

        if (session.getStatus() != ProjectDefinitionStatus.ACTIVE) {
            throw new IllegalStateException("Session is not active: " + session.getStatus());
        }

        session.setConversationHistory(appendMessage(session.getConversationHistory(), "user", answer));
        session = sessionRepository.save(session);

        try {
            String response = claudeService.continueConversation(session.getClaudeSessionId(), answer,
                    session.getConversationHistory(), claudeService.getMainRepoDir(), null)
                    .get(5, TimeUnit.MINUTES);

            String json = extractJsonBlock(response);
            JsonNode root = null;
            if (json != null) {
                try {
                    root = objectMapper.readTree(json);
                } catch (Exception parseEx) {
                    log.debug("Could not parse Claude response as JSON for session {}: {}",
                            sessionId, parseEx.getMessage());
                }
            }

            String type = root != null ? root.path("type").asText("question") : "question";
            session.setConversationHistory(appendMessage(session.getConversationHistory(), "assistant", response));

            if ("complete".equals(type)) {
                session.setStatus(ProjectDefinitionStatus.GENERATING);
                session = sessionRepository.save(session);
                broadcastState(session, root);
                generateAndSave(session);
                return toStateResponse(session);
            } else {
                session = sessionRepository.save(session);
                broadcastState(session, root);
                return toStateResponseWithQuestion(session, root);
            }
        } catch (Exception e) {
            log.error("Synchronous Claude call failed for session {}: {}", sessionId, e.getMessage(), e);
            markFailed(sessionId, "Something went wrong while processing your answer");
            throw new RuntimeException("Failed to process answer", e);
        }
    }

    /**
     * Parses a raw Claude response and transitions the session state accordingly.
     * Used by WebSocket-path callers; submitAnswer now handles this inline.
     */
    void handleClaudeResponse(Long sessionId, String rawResponse) {
        ProjectDefinitionSession session = sessionRepository.findById(sessionId).orElse(null);
        if (session == null) {
            log.warn("Session {} not found in handleClaudeResponse", sessionId);
            return;
        }
        if (session.getStatus() != ProjectDefinitionStatus.ACTIVE) {
            log.info("Session {} is no longer ACTIVE ({}), ignoring response", sessionId, session.getStatus());
            return;
        }

        try {
            String json = extractJsonBlock(rawResponse);
            JsonNode root = null;
            if (json != null) {
                try {
                    root = objectMapper.readTree(json);
                } catch (Exception parseEx) {
                    log.debug("Could not parse Claude response as JSON for session {}: {}",
                            sessionId, parseEx.getMessage());
                }
            }
            String type = root != null ? root.path("type").asText("question") : "question";

            session.setConversationHistory(appendMessage(session.getConversationHistory(), "assistant", rawResponse));

            if ("complete".equals(type)) {
                session.setStatus(ProjectDefinitionStatus.GENERATING);
                sessionRepository.save(session);
                broadcastState(session, root);
                generateAndSave(session);
            } else {
                sessionRepository.save(session);
                broadcastState(session, root);
            }
        } catch (Exception e) {
            log.error("Error handling Claude response for session {}", sessionId, e);
            markFailed(sessionId, "Something went wrong while processing the response");
        }
    }

    /**
     * Compile the conversation history into a PROJECT_DEFINITION.md document,
     * then push it to a branch and open a pull request.
     * Annotated @Async so it can be triggered from the Spring proxy; also runs
     * naturally async when called from a thenAccept callback.
     */
    @Async
    public void generateAndSave(ProjectDefinitionSession session) {
        try {
            String compilationPrompt = buildCompilationPrompt(session.isHasExistingDefinition());

            String generatedContent = claudeService.continueConversation(
                    session.getClaudeSessionId(),
                    compilationPrompt,
                    session.getConversationHistory(),
                    claudeService.getMainRepoDir(),
                    null
            ).get();

            session.setGeneratedContent(generatedContent);
            session.setStatus(ProjectDefinitionStatus.SAVING);
            sessionRepository.save(session);
            broadcastSimple(session);

            saveAndPushDefinition(session);

        } catch (Exception e) {
            log.error("Failed to generate project definition for session {}", session.getId(), e);
            markFailed(session.getId(), "Something went wrong while creating your project definition");
        }
    }

    /**
     * Commit PROJECT_DEFINITION.md to a new branch, push it, create a PR,
     * and optionally auto-merge. Sets status to COMPLETED or PR_OPEN.
     */
    void saveAndPushDefinition(ProjectDefinitionSession session) {
        SiteSettings settings = settingsService.getSettings();
        String repoUrl = settings.getTargetRepoUrl();
        String githubToken = settings.getGithubToken();
        String mainRepoDir = claudeService.getMainRepoDir();

        try {
            String defaultBranch = detectDefaultBranch(mainRepoDir);

            String timestamp = java.time.format.DateTimeFormatter
                    .ofPattern("yyyy-MM-dd-HHmmss")
                    .format(LocalDateTime.now());
            String branchName = "project-definition-" + timestamp;

            runGitCommand(mainRepoDir, "git", "fetch", "origin");
            runGitCommand(mainRepoDir, "git", "checkout", defaultBranch);
            runGitCommand(mainRepoDir, "git", "pull", "origin", defaultBranch);
            runGitCommand(mainRepoDir, "git", "checkout", "-b", branchName);

            Files.writeString(Paths.get(mainRepoDir, "PROJECT_DEFINITION.md"),
                    session.getGeneratedContent());

            runGitCommand(mainRepoDir, "git", "add", "PROJECT_DEFINITION.md");
            String commitMsg = session.isHasExistingDefinition()
                    ? "Update PROJECT_DEFINITION.md" : "Add PROJECT_DEFINITION.md";
            runGitCommand(mainRepoDir, "git", "commit", "-m", commitMsg);
            runGitCommand(mainRepoDir, "git", "push", "origin", branchName);

            session.setBranchName(branchName);

            if (githubToken == null || githubToken.isBlank()) {
                log.warn("No GitHub token configured — branch pushed but no PR created for session {}",
                        session.getId());
                session.setStatus(ProjectDefinitionStatus.PR_OPEN);
                session.setErrorMessage(
                        "Branch was submitted — create a pull request manually (no GitHub token configured)");
                session.setCompletedAt(LocalDateTime.now());
                sessionRepository.save(session);
                broadcastSimple(session);
                return;
            }

            String prTitle = session.isHasExistingDefinition()
                    ? "Update Project Definition" : "Add Project Definition";
            Map<String, Object> prResult = claudeService.createGitHubPullRequest(
                    repoUrl, branchName,
                    prTitle,
                    session.getGeneratedContent(),
                    githubToken
            );

            String prUrl = (String) prResult.get("html_url");
            int prNumber = ((Number) prResult.get("number")).intValue();

            session.setPrUrl(prUrl);
            session.setPrNumber(String.valueOf(prNumber));

            if (settings.isAutoMergePr()) {
                boolean merged = claudeService.mergePullRequest(repoUrl, prNumber, githubToken);
                if (merged) {
                    session.setStatus(ProjectDefinitionStatus.COMPLETED);
                } else {
                    session.setStatus(ProjectDefinitionStatus.PR_OPEN);
                    session.setErrorMessage("Auto-merge blocked — manual merge required");
                }
            } else {
                session.setStatus(ProjectDefinitionStatus.PR_OPEN);
            }

            session.setCompletedAt(LocalDateTime.now());
            sessionRepository.save(session);
            broadcastSimple(session);

        } catch (Exception e) {
            log.error("Failed in saveAndPushDefinition for session {}", session.getId(), e);
            markFailed(session.getId(), "Something went wrong while saving the project definition");
        }
    }

    /**
     * Return the most recent session's state, or null if no sessions exist.
     * For ACTIVE sessions, reconstructs the current question from conversation history
     * so the modal can resume where it left off.
     */
    public ProjectDefinitionStateResponse getState() {
        return sessionRepository.findTopByOrderByCreatedAtDesc()
                .map(session -> {
                    if (session.getStatus() == ProjectDefinitionStatus.ACTIVE) {
                        JsonNode lastQuestion = extractLastAssistantQuestion(session.getConversationHistory());
                        return toStateResponseWithQuestion(session, lastQuestion);
                    }
                    return toStateResponse(session);
                })
                .orElse(null);
    }

    /**
     * Walks the conversation history backwards to find the last assistant message
     * that contains a parseable JSON question block. Returns null if none found.
     */
    private JsonNode extractLastAssistantQuestion(String conversationHistory) {
        if (conversationHistory == null || conversationHistory.isBlank()) return null;
        try {
            ArrayNode history = (ArrayNode) objectMapper.readTree(conversationHistory);
            for (int i = history.size() - 1; i >= 0; i--) {
                JsonNode msg = history.get(i);
                if ("assistant".equals(msg.path("role").asText())) {
                    String json = extractJsonBlock(msg.path("content").asText());
                    if (json != null) {
                        try {
                            JsonNode parsed = objectMapper.readTree(json);
                            if ("question".equals(parsed.path("type").asText())) {
                                return parsed;
                            }
                        } catch (Exception ignored) {}
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Could not extract last question from history: {}", e.getMessage());
        }
        return null;
    }

    /**
     * Cancel any ACTIVE session by marking it FAILED.
     */
    @Transactional
    public void resetSession() {
        sessionRepository.findFirstByStatusIn(List.of(ProjectDefinitionStatus.ACTIVE))
                .ifPresent(session -> {
                    session.setStatus(ProjectDefinitionStatus.FAILED);
                    session.setErrorMessage("Session was reset");
                    session.setCompletedAt(LocalDateTime.now());
                    sessionRepository.save(session);
                    broadcastSimple(session);
                });
    }

    // ---- Existing definition reader ----

    /**
     * Reads PROJECT_DEFINITION.md from the main repo directory.
     * Returns the file content if it exists, or null if it does not.
     */
    public String readExistingProjectDefinition() {
        String mainRepoDir = claudeService.getMainRepoDir();
        java.nio.file.Path filePath = Paths.get(mainRepoDir, "PROJECT_DEFINITION.md");
        try {
            return Files.readString(filePath);
        } catch (java.nio.file.NoSuchFileException e) {
            return null;
        } catch (Exception e) {
            log.warn("Could not read PROJECT_DEFINITION.md from {}: {}", filePath, e.getMessage());
            return null;
        }
    }

    /**
     * Import a project definition by writing it to PROJECT_DEFINITION.md in main-repo.
     * Does not commit or push — writes to the local working copy only.
     */
    public void importProjectDefinition(String content) {
        String mainRepoDir = claudeService.getMainRepoDir();
        java.nio.file.Path filePath = Paths.get(mainRepoDir, "PROJECT_DEFINITION.md");
        File dir = new File(mainRepoDir);
        if (!dir.exists()) {
            throw new IllegalStateException(
                    "The target repository has not been cloned yet. Please configure a repository in Settings first.");
        }
        try {
            Files.writeString(filePath, content);
            log.info("Imported PROJECT_DEFINITION.md to {}", filePath);
        } catch (Exception e) {
            throw new RuntimeException("Failed to write PROJECT_DEFINITION.md: " + e.getMessage(), e);
        }
    }

    // ---- Prompt builders ----

    String buildInterviewPrompt(String existingDefinition) {
        String jsonFormat =
                "For each response, reply ONLY with JSON — no other text. " +
                "Use one of these formats:\n" +
                "Open-ended question: {\"type\":\"question\",\"questionType\":\"open\"," +
                "\"question\":\"...\",\"options\":[],\"progress\":<0-100>}\n" +
                "Multiple choice question: {\"type\":\"question\",\"questionType\":\"multiple_choice\"," +
                "\"question\":\"...\",\"options\":[\"option 1\",\"option 2\",...],\"progress\":<0-100>}\n" +
                "When finished: {\"type\":\"complete\",\"message\":\"...\"}\n\n" +
                "RULES:\n" +
                "- Use plain, everyday language. Never use technical jargon, tool names, " +
                "file names, code terms, or developer-specific terminology in your messages.\n" +
                "- Describe everything from the user's perspective, not a developer's.\n" +
                "- Set progress (0-100) based on how much useful information has been gathered.\n" +
                "- Ask one question at a time. Keep questions short and friendly.\n" +
                "- Adapt your follow-up questions based on what the user tells you — " +
                "explore interesting areas further and skip topics already covered.\n" +
                "- Send {\"type\":\"complete\"} when the user says they are satisfied, " +
                "says \"done\", or when you have gathered enough to write a thorough project description.";

        if (existingDefinition == null) {
            return "You are helping a user describe their software project for the first time. " +
                    "Have a friendly, conversational interview to understand what they are building.\n\n" +
                    "You must cover these 5 areas as your starting questions (ask them in order, " +
                    "one at a time, and follow up on interesting answers before moving to the next):\n" +
                    "1. What is the project about? What problem does it solve?\n" +
                    "2. Who are the main users or audience for this project?\n" +
                    "3. What are the most important things it needs to be able to do?\n" +
                    "4. Are there any constraints or limitations to keep in mind " +
                    "(budget, timeline, technology, team size, etc.)?\n" +
                    "5. What does success look like for this project? How will you know it is working well?\n\n" +
                    "After these 5 areas are covered, ask follow-up questions on anything " +
                    "that could use more detail. When the user is satisfied or you have gathered " +
                    "enough information, send the complete signal.\n\n" +
                    jsonFormat + "\n\n" +
                    "Start by asking what the project is about.";
        } else {
            return "You are helping a user refine and grow their existing project description. " +
                    "Your goal is to make the description more complete, detailed, and useful " +
                    "by asking targeted follow-up questions about areas that need more depth.\n\n" +
                    "Here is the current description:\n\n" +
                    "---\n" + existingDefinition + "\n---\n\n" +
                    "Start by briefly summarizing what you see (2-3 sentences, in plain language), " +
                    "then immediately ask a specific follow-up question about an area of the " +
                    "description that could benefit from more detail.\n\n" +
                    "Analyze the current description and proactively ask questions to expand it. " +
                    "Focus on:\n" +
                    "- Areas that are mentioned but lack detail — ask for specifics\n" +
                    "- Missing perspectives — who else uses this? What edge cases exist?\n" +
                    "- Goals that could be more concrete — what does success look like in numbers?\n" +
                    "- Features mentioned briefly — how should they actually work?\n" +
                    "- Things that may have evolved — any new plans, users, or capabilities?\n" +
                    "- Technical details that could help guide development\n" +
                    "- Priorities and trade-offs that aren't captured yet\n\n" +
                    "Do NOT just ask \"has anything changed?\" — instead, pick a specific area " +
                    "that needs more depth and ask a focused question about it. Keep asking " +
                    "follow-up questions to grow the description until the user says they are " +
                    "satisfied or says \"done\".\n\n" +
                    "Ask one question at a time. Each question should target a specific gap " +
                    "or opportunity to add useful detail to the project description.\n\n" +
                    jsonFormat + "\n\n" +
                    "Start by summarizing what you see and asking your first refinement question.";
        }
    }

    private String buildCompilationPrompt(boolean isRefinement) {
        if (isRefinement) {
            return "Based on our conversation, please produce an updated and expanded " +
                    "PROJECT_DEFINITION.md document. Merge all the new information we " +
                    "discussed into the existing definition — keep everything that is still " +
                    "accurate, update anything that has changed, and add the new details " +
                    "we covered.\n\n" +
                    "Structure the document with these sections:\n" +
                    "# Project Definition\n" +
                    "## Overview\n" +
                    "## Motivation\n" +
                    "## Goals\n" +
                    "## Target Users\n" +
                    "## How Users Will Interact With It\n" +
                    "## Current Stage\n" +
                    "## Key Features\n" +
                    "## Technical Constraints\n" +
                    "## 6-Month Vision\n\n" +
                    "The result should be a single, cohesive document that reads as a " +
                    "complete project description — not a changelog or diff. " +
                    "Write the complete markdown document. " +
                    "Do not include any JSON wrapper or preamble — output only the markdown.";
        }
        return "Based on our conversation so far, please compile a comprehensive " +
                "PROJECT_DEFINITION.md document that captures everything we discussed " +
                "about this software project.\n\n" +
                "Structure the document with these sections:\n" +
                "# Project Definition\n" +
                "## Overview\n" +
                "## Motivation\n" +
                "## Goals\n" +
                "## Target Users\n" +
                "## How Users Will Interact With It\n" +
                "## Current Stage\n" +
                "## Key Features\n" +
                "## Technical Constraints\n" +
                "## 6-Month Vision\n\n" +
                "Write the complete markdown document. " +
                "Do not include any JSON wrapper or preamble — output only the markdown.";
    }

    // ---- State response builder ----

    ProjectDefinitionStateResponse toStateResponse(ProjectDefinitionSession session) {
        ProjectDefinitionStateResponse resp = new ProjectDefinitionStateResponse();
        resp.setSessionId(session.getId());
        resp.setStatus(session.getStatus());
        resp.setGeneratedContent(session.getGeneratedContent());
        resp.setPrUrl(session.getPrUrl());
        resp.setErrorMessage(session.getErrorMessage());
        resp.setIsEdit(session.isHasExistingDefinition());
        return resp;
    }

    ProjectDefinitionStateResponse toStateResponseWithQuestion(ProjectDefinitionSession session, JsonNode questionNode) {
        ProjectDefinitionStateResponse resp = toStateResponse(session);
        if (questionNode != null) {
            String type = questionNode.path("type").asText("question");
            if (!"complete".equals(type)) {
                resp.setCurrentQuestion(questionNode.path("question").asText(""));
                resp.setQuestionType(questionNode.path("questionType").asText("open"));
                resp.setProgressPercent(questionNode.path("progress").asInt(0));

                JsonNode optionsNode = questionNode.path("options");
                List<String> options = new ArrayList<>();
                if (optionsNode.isArray()) {
                    for (JsonNode opt : optionsNode) {
                        options.add(opt.asText());
                    }
                }
                resp.setOptions(options);
            }
        }
        return resp;
    }

    // ---- Broadcast helpers ----

    private void broadcastState(ProjectDefinitionSession session, JsonNode questionNode) {
        Map<String, Object> data = new HashMap<>();
        data.put("sessionId", session.getId());
        data.put("status", session.getStatus().name());

        if (questionNode != null) {
            data.put("currentQuestion", questionNode.path("question").asText(""));
            data.put("questionType", questionNode.path("questionType").asText("open"));
            data.put("progressPercent", questionNode.path("progress").asInt(0));

            JsonNode optionsNode = questionNode.path("options");
            List<String> options = new ArrayList<>();
            if (optionsNode.isArray()) {
                for (JsonNode opt : optionsNode) {
                    options.add(opt.asText());
                }
            }
            data.put("options", options);
        }

        Map<String, Object> payload = new HashMap<>();
        payload.put("type", "PROJECT_DEFINITION_UPDATE");
        payload.put("data", data);
        broadcastToAdmins(payload);
    }

    void broadcastSimple(ProjectDefinitionSession session) {
        Map<String, Object> data = new HashMap<>();
        data.put("sessionId", session.getId());
        data.put("status", session.getStatus().name());
        if (session.getPrUrl() != null) {
            data.put("prUrl", session.getPrUrl());
        }
        if (session.getErrorMessage() != null) {
            data.put("errorMessage", session.getErrorMessage());
        }

        Map<String, Object> payload = new HashMap<>();
        payload.put("type", "PROJECT_DEFINITION_UPDATE");
        payload.put("data", data);
        broadcastToAdmins(payload);
    }

    private void broadcastToAdmins(Map<String, Object> payload) {
        List<User> admins = new ArrayList<>();
        admins.addAll(userRepository.findByRole(UserRole.ROOT_ADMIN));
        admins.addAll(userRepository.findByRole(UserRole.ADMIN));
        for (User admin : admins) {
            if (admin.getUsername() != null) {
                notificationHandler.sendNotificationToUser(admin.getUsername(), payload);
            }
        }
    }

    // ---- Failure handling ----

    void markFailed(Long sessionId, String message) {
        sessionRepository.findById(sessionId).ifPresent(session -> {
            session.setStatus(ProjectDefinitionStatus.FAILED);
            session.setErrorMessage(message);
            session.setCompletedAt(LocalDateTime.now());
            sessionRepository.save(session);
            broadcastSimple(session);
        });
    }

    // ---- JSON helpers ----

    String appendMessage(String historyJson, String role, String content) {
        try {
            ArrayNode array = (historyJson != null && !historyJson.isBlank())
                    ? (ArrayNode) objectMapper.readTree(historyJson)
                    : objectMapper.createArrayNode();
            ObjectNode msg = objectMapper.createObjectNode();
            msg.put("role", role);
            msg.put("content", content);
            array.add(msg);
            return objectMapper.writeValueAsString(array);
        } catch (Exception e) {
            log.warn("Failed to append message to conversation history: {}", e.getMessage());
            return historyJson != null ? historyJson : "[]";
        }
    }

    String extractJsonBlock(String response) {
        if (response == null) return null;
        int start = response.indexOf('{');
        int end = response.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return response.substring(start, end + 1);
        }
        return null;
    }

    // ---- Git helpers (protected for testability) ----

    protected String detectDefaultBranch(String repoDir) {
        // Delegate to ClaudeService so the command runs as the configured run-as user
        // when the JVM runs as root — otherwise git would create files in .git/ owned
        // by root and later runuser-wrapped git operations would fail with EACCES.
        try {
            int exitCode = claudeService.runGitCommandAsUserExitCode(
                    repoDir, "git", "rev-parse", "--verify", "origin/main");
            return exitCode == 0 ? "main" : "master";
        } catch (Exception e) {
            log.warn("Could not detect default branch, defaulting to main: {}", e.getMessage());
            return "main";
        }
    }

    protected void runGitCommand(String repoDir, String... command) throws Exception {
        // Delegate to ClaudeService so the command runs as the configured run-as user
        // when the JVM runs as root — otherwise git would create files in .git/ owned
        // by root and later runuser-wrapped git operations would fail with EACCES.
        claudeService.runGitCommandAsUser(repoDir, command);
    }
}
