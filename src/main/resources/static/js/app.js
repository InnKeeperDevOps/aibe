/**
 * ES module entry point.
 *
 * Imports all module functions, wires up inter-module callbacks, and exposes
 * window.app so that existing inline HTML onclick="app.foo()" handlers keep
 * working without modifying the templates.
 */

import { state } from './modules/state.js';
import { api } from './modules/api.js';
import { showToast } from './modules/utils.js';

import {
    registerAuthCallbacks,
    checkAuth,
    updateHeader,
    initProjectDefinition,
    setup,
    login,
    logout,
    register,
} from './modules/auth.js';

import {
    registerNavigationCallbacks,
    navigate,
} from './modules/navigation.js';

import {
    onSearchInput,
    applyFilters,
    loadSuggestions,
    restoreFiltersFromUrl,
    renderSuggestionItem,
} from './modules/suggestions.js';

import {
    registerSuggestionDetailCallbacks,
    loadDetail,
    renderMessages,
    renderMessage,
    approve,
    deny,
    changePriority,
    approveSuggestion,
    denySuggestion,
    submitDenySuggestion,
    retryPr,
    retryMerge,
    retryExecution,
    retryFromLast,
    restartPlan,
    forceReApproval,
    toggleDetailedPlan,
    vote,
} from './modules/suggestionDetail.js';

import { renderTasks, updateTask } from './modules/tasks.js';

import {
    renderExpertReview,
    updateExpertReview,
    addExpertNote,
    renderExpertNotes,
    loadReviewSummary,
    renderReviewSummary,
    toggleReviewSummary,
    showFullReviews,
    showExpertClarificationWizard,
    renderExpertClarificationStep,
    submitExpertClarifications,
} from './modules/expertReview.js';

import {
    showClarificationWizard,
    hideClarificationWizard,
    renderClarificationStep,
    saveClarificationAnswer,
    nextClarification,
    prevClarification,
    submitClarifications,
    loadClarificationQuestions,
} from './modules/clarification.js';

import {
    showMyDrafts,
    showAllSuggestions,
    loadMyDrafts,
    renderDraftCards,
    openEditDraftModal,
    closeEditDraftModal,
    saveEditDraft,
    submitDraftConfirm,
} from './modules/drafts.js';

import {
    fetchRecommendations,
    pollRecommendationTask,
    renderRecommendationsError,
    closeRecommendationsModal,
    prefillFromRecommendation,
    openRecommendationsHistory,
    closeRecommendationsHistory,
    viewRecommendationRun,
    renderRecommendationsHistoryList,
    renderRecommendationRunDetail,
    renderActiveRecommendations,
    applyRecommendationsHistoryFilters,
    clearRecommendationsHistoryFilters,
    refreshRecommendationsHistory,
    startFreshRecommendationsRun,
    rerunRecommendationRun,
    markRecommendationActedOn,
} from './modules/recommendations.js';

import {
    openProjectDefinition,
    startNewProjectDefinition,
    showProjectDefinitionModal,
    submitProjectDefinitionAnswer,
    renderProjectDefinitionComplete,
    expandProjectDefinitionContent,
    closeProjectDefinitionModal,
    retryProjectDefinition,
    downloadProjectDefinition,
    openImportDefinitionModal,
    closeImportDefinitionModal,
    handleImportFileSelect,
    processImportFile,
    clearImportFile,
    formatFileSize,
    submitImportDefinition,
    onProjectDefinitionUpdate,
} from './modules/projectDefinition.js';

import {
    loadClaudeLogs,
    viewClaudeLog,
    loadClaudeLogDetail,
} from './modules/claudeLogs.js';

import {
    loadClaudeQueue,
    stopClaudeQueuePolling,
} from './modules/claudeQueue.js';

import {
    loadSpendingDashboard,
} from './modules/spendingDashboard.js';

import {
    loadPlans,
    loadPlanDetail,
    loadAllTasks,
    filterPlans,
    filterAllTasks,
    toggleLowLevelDetail,
} from './modules/plans.js';

import {
    openChangePasswordModal,
    closeChangePasswordModal,
    submitChangePassword,
} from './modules/changePassword.js';

import {
    loadSettings,
    loadGroups,
    editGroup,
    cancelGroupEdit,
    saveGroup,
    deleteGroup,
    showUserTab,
    loadPendingUsers,
    approveUser,
    denyUser,
    loadAllUsers,
    assignUserGroup,
    saveSettings,
    generateGitSshKey,
    copyGitSshPublicKey,
    createAdmin,
} from './modules/settings.js';

import {
    refreshClaudeCliLogin,
    startClaudeCliLogin,
    submitClaudeCliCode,
    cancelClaudeCliLogin,
    clearClaudeCliCredentials,
} from './modules/claudeCliLogin.js';

import {
    loadDashboardView,
    renderLeaderboard,
    renderUserHistory,
} from './modules/dashboard.js';

import {
    connectWs,
    disconnectWs,
    connectNotificationsWs,
    updateApprovalBanner,
} from './modules/websocket.js';

// ---------------------------------------------------------------------------
// Functions not yet extracted to dedicated modules
// ---------------------------------------------------------------------------

async function createSuggestion(e) {
    e.preventDefault();
    const data = await api('/suggestions', {
        method: 'POST',
        body: JSON.stringify({
            title: document.getElementById('createTitle').value,
            description: document.getElementById('createDescription').value,
            authorName: document.getElementById('createAuthorName').value || undefined,
            priority: document.getElementById('createPriority').value || 'MEDIUM'
        })
    });
    if (data.error) {
        showToast(data.error);
        return;
    }
    // If this suggestion was created from an AI recommendation, mark that
    // recommendation as acted-on so it no longer appears in the active list.
    await markRecommendationActedOn(data.id);
    document.getElementById('createForm').reset();
    navigate('detail', data.id);
}

async function saveAsDraft(e) {
    e.preventDefault();
    const data = await api('/suggestions', {
        method: 'POST',
        body: JSON.stringify({
            title: document.getElementById('createTitle').value,
            description: document.getElementById('createDescription').value,
            priority: document.getElementById('createPriority').value || 'MEDIUM',
            isDraft: true
        })
    });
    if (data.error) {
        showToast(data.error);
        return;
    }
    document.getElementById('createForm').reset();
    showToast('Draft saved.');
    navigate('list');
}

async function reply() {
    const input = document.getElementById('replyInput');
    const content = input.value.trim();
    if (!content) return;
    input.value = '';
    const id = state.currentSuggestion;
    await api('/suggestions/' + id + '/messages', {
        method: 'POST',
        body: JSON.stringify({ content, senderName: state.username || undefined })
    });
}

// ---------------------------------------------------------------------------
// Wire up cross-module callbacks
// ---------------------------------------------------------------------------

registerAuthCallbacks({
    connectNotificationsWs,
    showProjectDefinitionModal,
});

registerNavigationCallbacks({
    loadSuggestions,
    loadDashboardView,
    loadDetail,
    loadSettings,
    loadClaudeLogs,
    loadClaudeLogDetail,
    loadClaudeQueue,
    stopClaudeQueuePolling,
    loadSpendingDashboard,
    loadPlans,
    loadPlanDetail,
    loadAllTasks,
    disconnectWs,
    updateSaveAsDraftBtn: () => {
        const btn = document.getElementById('saveAsDraftBtn');
        if (!btn) return;
        const allowAnon = state.settings.allowAnonymousSuggestions;
        const hasPermission = state.permissions.includes('CREATE_SUGGESTIONS');
        btn.style.display = (state.loggedIn && (hasPermission || allowAnon)) ? '' : 'none';
    },
});

registerSuggestionDetailCallbacks({
    connectWs,
    showToast,
    updateApprovalBanner,
});

// ---------------------------------------------------------------------------
// Assemble window.app for inline HTML event handlers
// ---------------------------------------------------------------------------

window.app = {
    state,

    // auth
    checkAuth,
    updateHeader,
    initProjectDefinition,
    setup,
    login,
    logout,
    register,

    // navigation
    navigate,

    // suggestions list
    loadSuggestions,
    onSearchInput,
    applyFilters,
    restoreFiltersFromUrl,
    renderSuggestionItem,

    // suggestion detail
    loadDetail,
    renderMessages,
    renderMessage,
    approve,
    deny,
    changePriority,
    approveSuggestion,
    denySuggestion,
    submitDenySuggestion,
    retryPr,
    retryMerge,
    retryExecution,
    retryFromLast,
    restartPlan,
    forceReApproval,
    toggleDetailedPlan,
    vote,

    // tasks
    renderTasks,
    updateTask,

    // expert review
    renderExpertReview,
    updateExpertReview,
    addExpertNote,
    renderExpertNotes,
    loadReviewSummary,
    renderReviewSummary,
    toggleReviewSummary,
    showFullReviews,
    showExpertClarificationWizard,
    renderExpertClarificationStep,
    submitExpertClarifications,

    // clarification
    showClarificationWizard,
    hideClarificationWizard,
    renderClarificationStep,
    saveClarificationAnswer,
    nextClarification,
    prevClarification,
    submitClarifications,
    loadClarificationQuestions,

    // drafts
    showMyDrafts,
    showAllSuggestions,
    loadMyDrafts,
    renderDraftCards,
    openEditDraftModal,
    closeEditDraftModal,
    saveEditDraft,
    submitDraftConfirm,

    // recommendations
    fetchRecommendations,
    pollRecommendationTask,
    renderRecommendationsError,
    closeRecommendationsModal,
    prefillFromRecommendation,
    openRecommendationsHistory,
    closeRecommendationsHistory,
    viewRecommendationRun,
    renderRecommendationsHistoryList,
    renderRecommendationRunDetail,
    renderActiveRecommendations,
    applyRecommendationsHistoryFilters,
    clearRecommendationsHistoryFilters,
    refreshRecommendationsHistory,
    startFreshRecommendationsRun,
    rerunRecommendationRun,
    markRecommendationActedOn,

    // project definition
    openProjectDefinition,
    startNewProjectDefinition,
    showProjectDefinitionModal,
    submitProjectDefinitionAnswer,
    renderProjectDefinitionComplete,
    expandProjectDefinitionContent,
    closeProjectDefinitionModal,
    retryProjectDefinition,
    downloadProjectDefinition,
    openImportDefinitionModal,
    closeImportDefinitionModal,
    handleImportFileSelect,
    processImportFile,
    clearImportFile,
    formatFileSize,
    submitImportDefinition,
    onProjectDefinitionUpdate,

    // claude logs
    loadClaudeLogs,
    viewClaudeLog,
    loadClaudeLogDetail,

    // claude queue
    loadClaudeQueue,
    stopClaudeQueuePolling,

    // spending dashboard
    loadSpendingDashboard,

    // plans / tasks (cross-suggestion)
    loadPlans,
    loadPlanDetail,
    loadAllTasks,
    filterPlans,
    filterAllTasks,
    toggleLowLevelDetail,

    // change password
    openChangePasswordModal,
    closeChangePasswordModal,
    submitChangePassword,

    // settings / admin
    loadSettings,
    loadGroups,
    editGroup,
    cancelGroupEdit,
    saveGroup,
    deleteGroup,
    showUserTab,
    loadPendingUsers,
    approveUser,
    denyUser,
    loadAllUsers,
    assignUserGroup,
    saveSettings,
    generateGitSshKey,
    copyGitSshPublicKey,
    createAdmin,

    // claude cli login
    refreshClaudeCliLogin,
    startClaudeCliLogin,
    submitClaudeCliCode,
    cancelClaudeCliLogin,
    clearClaudeCliCredentials,

    // dashboard
    loadDashboardView,
    renderLeaderboard,
    renderUserHistory,

    // websocket
    connectWs,
    disconnectWs,
    connectNotificationsWs,
    updateApprovalBanner,

    // create / draft / reply (not in dedicated modules)
    createSuggestion,
    saveAsDraft,
    reply,

    // main menu (hamburger)
    toggleMainMenu,
    closeMainMenu,

    // utilities
    showToast,
};

// ---------------------------------------------------------------------------
// Bootstrap
// ---------------------------------------------------------------------------

// ---------------------------------------------------------------------------
// Main hamburger menu — open/close, outside-click, Escape, auto-close on nav
// ---------------------------------------------------------------------------

function toggleMainMenu(event) {
    if (event) event.stopPropagation();
    const sidebar = document.getElementById('appSidebar');
    const backdrop = document.getElementById('sidebarBackdrop');
    const toggle = document.getElementById('menuToggle');
    if (!sidebar) return;
    const willOpen = !sidebar.classList.contains('open');
    sidebar.classList.toggle('open', willOpen);
    if (backdrop) backdrop.classList.toggle('open', willOpen);
    if (toggle) toggle.setAttribute('aria-expanded', String(willOpen));
}

function closeMainMenu() {
    const sidebar = document.getElementById('appSidebar');
    const backdrop = document.getElementById('sidebarBackdrop');
    const toggle = document.getElementById('menuToggle');
    if (sidebar) sidebar.classList.remove('open');
    if (backdrop) backdrop.classList.remove('open');
    if (toggle) toggle.setAttribute('aria-expanded', 'false');
}

function _initMainMenu() {
    const sidebar = document.getElementById('appSidebar');
    if (!sidebar) return;
    // Click a sidebar nav item -> close drawer (mobile).
    sidebar.addEventListener('click', (e) => {
        const target = e.target.closest('.sidebar-item');
        if (target) closeMainMenu();
    });
    // Escape -> close (mobile drawer).
    document.addEventListener('keydown', (e) => {
        if (e.key === 'Escape') closeMainMenu();
    });
}

document.addEventListener('DOMContentLoaded', () => {
    _initMainMenu();
    checkAuth();
});
